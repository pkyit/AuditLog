#!/bin/bash
# 真 A/B：分别构建"优化前"与"优化后"两个真实制品，
# 在**完全相同的压测口径**下对比（同并发度、同时长、同核心绑定、同轮数）。
#
# 目录：
#   /root/audit-log            <- 优化后（当前工作区）
#   /root/audit-log-baseline   <- 优化前（commit b32ba0a）
set -u
source /etc/profile.d/audit-java17.sh
source /root/bench-lib.sh

ROUNDS="${1:-4}"
CONN="${2:-64}"
PAYLOAD=$(python3 -c 'print("p"*180)')
BASE_SRC=/root/audit-log-baseline
OPT_SRC=/root/audit-log

build() {
  local src="$1" label="$2"
  echo "=== 构建 ${label}: ${src} ==="
  ( cd "$src" && mvn -B -q -DskipTests -Pfat-jar -pl audit-log-samples -am package 2>&1 | tail -5 )
  local jar="$src/audit-log-samples/target/audit-log-samples-1.0.0.jar"
  if [ ! -f "$jar" ]; then echo "!! 构建失败: $jar"; return 1; fi
  ls -l --time-style=+%H:%M "$jar"
}

run_jar() {
  local jar="$1" label="$2" outdir="$3" batch="$4"
  mkdir -p "$outdir"
  for i in $(seq 1 "$ROUNDS"); do
    # 文件名必须同时带上「批次」与「轮次」：早期版本只用 run${i}，
    # 两批交替执行时后一批会覆盖前一批，导致样本丢失。
    local outfile="$outdir/run-b${batch}-r${i}.txt"
    log "===== ${label} batch${batch} round ${i}/${ROUNDS} (c=${CONN}) ====="
    rm -rf /root/bench/journal /root/bench/audit
    mkdir -p /root/bench/journal /root/bench/audit
    nohup taskset -c "$CPUS" java $HEAP \
        -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
        -Dspring.profiles.active=bench \
        -jar "$jar" > "/root/bench/results/app-${label}${batch}-${i}.log" 2>&1 &
    echo $! > "/root/bench/results/app-${label}${batch}-${i}.pid"
    wait_ready "/root/bench/results/app-${label}${batch}-${i}.pid" || { log "start failed"; stop_app "${label}${i}"; continue; }
    sleep 3
    local url="${BASE}/load/full?payload=${PAYLOAD}"
    taskset -c "$LOAD_CPUS" wrk -t2 -c32 -d15s "$url" > /dev/null 2>&1
    taskset -c "$LOAD_CPUS" wrk -t2 -c${CONN} -d45s --latency "$url" > "$outfile" 2>&1
    stop_app "${label}${i}"
  done
}

# 清理旧样本，避免历史文件混入统计
rm -rf /root/bench/realab-base /root/bench/realab-opt
mkdir -p /root/bench/realab-base /root/bench/realab-opt

build "$BASE_SRC" "baseline" || exit 1
build "$OPT_SRC"  "optimized" || exit 1

BASE_JAR="$BASE_SRC/audit-log-samples/target/audit-log-samples-1.0.0.jar"
OPT_JAR="$OPT_SRC/audit-log-samples/target/audit-log-samples-1.0.0.jar"

# 交替执行：每批各跑一轮，降低"先后顺序"带来的系统性偏差
for i in $(seq 1 "$ROUNDS"); do
  run_jar "$BASE_JAR" "base" "/root/bench/realab-base" "$i"
  run_jar "$OPT_JAR"  "opt"  "/root/bench/realab-opt"  "$i"
done

echo
echo "==================== 真 A/B 结果 (c=${CONN}, ${ROUNDS} 轮交替) ===================="
python3 - <<'PY'
import glob, re, statistics
def collect(d):
    rps, p99 = [], []
    for f in sorted(glob.glob(d + "/run*.txt")):
        t = open(f, errors="replace").read()
        m = re.search(r"Requests/sec:\s+([\d.]+)", t)
        if m: rps.append(float(m.group(1)))
        m2 = re.search(r"^\s+99%\s+([\d.]+)(ms|s)", t, re.M)
        if m2: p99.append(float(m2.group(1)) * (1000 if m2.group(2)=="s" else 1))
    return rps, p99
res = {}
for mode, label in (("base","优化前 (b32ba0a)"), ("opt","优化后 (当前)")):
    rps, p99 = collect("/root/bench/realab-%s" % mode)
    res[mode] = statistics.median(rps) if rps else 0
    if rps:
        print("%-18s n=%d  中位数=%8.0f rps  min=%8.0f max=%8.0f  波动=%4.1f%%  P99中位=%5.0fms"
              % (label, len(rps), statistics.median(rps), min(rps), max(rps),
                 (max(rps)-min(rps))/statistics.median(rps)*100,
                 statistics.median(p99) if p99 else -1))
    else:
        print("%-18s 无有效样本" % label)
if res.get("base") and res.get("opt"):
    print()
    print("优化后相对优化前: %+.1f%%" % ((res["opt"]-res["base"])/res["base"]*100))
PY
