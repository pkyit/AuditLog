#!/bin/bash
# 严格隔离的真 A/B：
#   * 每次运行使用**唯一端口**（从 19100 递增），并发运行也不会互相干扰
#     —— 之前两批用同一端口 18083，导致测量互相污染
#   * 文件锁防止同一脚本被重复启动（我曾因此并发跑了两份，数据全废）
#   * base / opt 交替执行，降低顺序效应
set -u
source /etc/profile.d/audit-java17.sh

LOCK=/var/lock/audit-bench.lock
exec 9>"$LOCK"
if ! flock -n 9; then
  echo "!! 已有压测在运行（锁 $LOCK 被占用），本次退出"
  exit 1
fi

ROUNDS="${1:-3}"
CONN="${2:-64}"
JAVA=/usr/lib/jvm/java-17-openjdk-17.0.12.7-3.oe2409.x86_64/bin/java
[ -x "$JAVA" ] || JAVA=$(command -v java)
CAP=0-3
LOAD=4-5
HEAP="-Xms1g -Xmx1g"
PAYLOAD=$(python3 -c 'print("p"*180)')
OUT=/root/bench/iso
rm -rf "$OUT"; mkdir -p "$OUT"

log() { echo "[$(date +%H:%M:%S)] $*"; }

log "JAVA=$JAVA"

# 等待端口真正释放。
# 关键教训：上一轮 kill -9 结束后端口可能仍被占用，下一个应用会绑定失败并立刻退出，
# 而压测脚本仍会写出一个"结果文件"——看起来像一次正常测量，实际什么都没测到。
wait_port_free() {
  local port="$1"
  for i in $(seq 1 60); do
    if ! ss -lnt 2>/dev/null | grep -q ":${port} "; then return 0; fi
    sleep 1
  done
  log "!! 端口 ${port} 在 60s 内未释放"
  return 1
}

run_once() {
  local jar="$1" label="$2" port="$3" outfile="$4"
  local base="http://127.0.0.1:${port}"
  wait_port_free "$port" || return 1
  rm -rf /root/bench/journal /root/bench/audit
  mkdir -p /root/bench/journal /root/bench/audit
  nohup taskset -c "$CAP" "$JAVA" $HEAP -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
      -Dspring.profiles.active=bench -Dserver.port="$port" \
      -jar "$jar" > "/root/bench/iso/app-${label}-${port}.log" 2>&1 &
  local pid=$!
  local ok=0
  local waited=0
  # 就绪判定放宽到 120s：启动本身慢时 60s 会误判为失败
  for i in $(seq 1 120); do
    waited=$i
    if curl -s -m 2 -o /dev/null "${base}/actuator/health"; then ok=1; break; fi
    kill -0 "$pid" 2>/dev/null || { log "!! 进程已退出（见 app-${label}-${port}.log）"; break; }
    sleep 1
  done
  if [ "$ok" != "1" ]; then
    log "!! ${label}@${port} 启动失败（等待 ${waited}s）"
    kill -9 "$pid" 2>/dev/null
    return 1          # 不写结果文件 -> 调用方不计入样本
  fi
  sleep 3
  local url="${base}/load/full?payload=${PAYLOAD}"
  taskset -c "$LOAD" wrk -t2 -c32 -d15s "$url" > /dev/null 2>&1
  taskset -c "$LOAD" wrk -t2 -c${CONN} -d45s --latency "$url" > "$outfile" 2>&1
  kill "$pid" 2>/dev/null
  for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null
  wait_port_free "$port" || true
  sleep 2
  return 0
}

BASE_JAR=/root/audit-log-baseline/audit-log-samples/target/audit-log-samples-1.0.0.jar
OPT_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
for j in "$BASE_JAR" "$OPT_JAR"; do
  [ -f "$j" ] || { log "!! 缺少制品: $j"; exit 1; }
done

PORT=19100
for i in $(seq 1 "$ROUNDS"); do
  log "===== 第 ${i}/${ROUNDS} 轮 ====="
  # 关键：按 jar 路径自我识别模式，而不是"按调用顺序推断"。
  # 早期版本靠调用位置决定文件名，一旦某次启动失败消耗掉一个端口，
  # 后续的 base/opt 就会错位，导致某个模式只拿到 1 个样本。
  if [ $((i % 2)) -eq 1 ]; then
    run_once "$BASE_JAR" "base" "$PORT" "$OUT/base-r${i}.txt"; PORT=$((PORT+1))
    run_once "$OPT_JAR"  "opt"  "$PORT" "$OUT/opt-r${i}.txt";  PORT=$((PORT+1))
  else
    run_once "$OPT_JAR"  "opt"  "$PORT" "$OUT/opt-r${i}.txt";  PORT=$((PORT+1))
    run_once "$BASE_JAR" "base" "$PORT" "$OUT/base-r${i}.txt"; PORT=$((PORT+1))
  fi
done

# 断言两种模式拿到相同数量的样本；不等就明确报错，而不是给出误导性结论
NB=$(ls -1 "$OUT"/base-r*.txt 2>/dev/null | wc -l)
NO=$(ls -1 "$OUT"/opt-r*.txt 2>/dev/null | wc -l)
if [ "$NB" != "$NO" ]; then
  echo
  echo "!! 样本数不相等（base=$NB, opt=$NO）—— 本次对比不可信，不得据此下结论。"
  echo "!! 请清理后重跑：bash /root/hard-clean.sh && bash /root/iso-ab.sh $ROUNDS $CONN"
fi

echo
echo "==================== 隔离 A/B 结果 (c=${CONN}) ===================="
python3 - <<'PY'
import glob, re, statistics
def collect(d, prefix):
    rps, p99 = [], []
    for f in sorted(glob.glob("%s/%s-r*.txt" % (d, prefix))):
        t = open(f, errors="replace").read()
        m = re.search(r"Requests/sec:\s+([\d.]+)", t)
        if m: rps.append(float(m.group(1)))
        m2 = re.search(r"^\s+99%\s+([\d.]+)(ms|s)", t, re.M)
        if m2: p99.append(float(m2.group(1)) * (1000 if m2.group(2) == "s" else 1))
    return rps, p99
res = {}
for prefix, label in (("base", "优化前 (b32ba0a)"), ("opt", "优化后 (当前)")):
    rps, p99 = collect("/root/bench/iso", prefix)
    res[prefix] = statistics.median(rps) if rps else 0
    if rps:
        print("%-18s n=%d  中位数=%8.0f rps  min=%8.0f max=%8.0f  波动=%4.1f%%  P99中位=%5.0fms"
              % (label, len(rps), statistics.median(rps), min(rps), max(rps),
                 (max(rps) - min(rps)) / statistics.median(rps) * 100,
                 statistics.median(p99) if p99 else -1))
        print("                   逐轮: %s" % ", ".join("%.0f" % v for v in rps))
    else:
        print("%-18s 无有效样本" % label)
if res.get("base") and res.get("opt"):
    print()
    print("优化后 / 优化前 = %.3f  (%+.1f%%)" % (
        res["opt"] / res["base"], (res["opt"] - res["base"]) / res["base"] * 100))
PY
