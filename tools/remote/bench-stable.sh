#!/bin/bash
# 稳定性优先的 A/B 配置：
#   * 固定线程数（避免连接排队成为主导因素）
#   * 固定测量时长，多轮取中位数
#   * 关闭 Spring 自身观测，减少噪声源
# 用法: bash bench-stable.sh <标签> <轮数> [c]
set -u
source /root/bench-lib.sh

TAG="${1:-stable}"
ROUNDS="${2:-5}"
CONN="${3:-64}"
CPUS="${CPUS:-0-3}"
LOAD_CPUS="${LOAD_CPUS:-4-5}"

OUT=/root/bench/stable-${TAG}
mkdir -p "$OUT"
RESULT_DIR="$OUT"
export RESULT_DIR

PAYLOAD=$(python3 -c 'print("p"*180)')

for i in $(seq 1 "$ROUNDS"); do
  log "===== ${TAG} round ${i}/${ROUNDS} (c=${CONN}) ====="

  # 注意：文件名必须带上轮次 i —— 早期版本把 full 固定写成 full1，
  # 导致每轮互相覆盖，只剩最后一轮结果（测量数据丢失）。
  for spec in "boundary:${i}:${BASE}/load/plain?payload=hello" "full:${i}:${BASE}/load/full?payload=${PAYLOAD}"; do
    name="${spec%%:*}"
    rest="${spec#*:}"
    idx="${rest%%:*}"
    url="${rest#*:}"
    start_app "${name}${idx}" "bench" || { log "start failed ${name}${idx}"; stop_app "${name}${idx}"; continue; }
    taskset -c "$LOAD_CPUS" wrk -t2 -c32 -d15s "$url" > /dev/null 2>&1
    taskset -c "$LOAD_CPUS" wrk -t2 -c${CONN} -d45s --latency "$url" > "$OUT/${name}${idx}.txt" 2>&1
    stop_app "${name}${idx}"
  done
done

echo
echo "==================== [${TAG}] 结果 (c=${CONN}) ===================="
printf '%-10s %6s %14s %12s\n' "场景" "轮次" "rps" "P99"
for f in $(ls -1 "$OUT"/*.txt 2>/dev/null | sort); do
  n=$(basename "$f" .txt)
  r=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
  p=$(awk '/^[[:space:]]+99%/{print $2}' "$f")
  printf '%-10s %6s %14s %12s\n' "$n" "" "${r:-n/a}" "${p:-n/a}"
done

echo
python3 - "$OUT" <<'PY'
import glob, os, re, statistics
d = os.sys.argv[1]
def col(prefix):
    rps, p99 = [], []
    for f in glob.glob(os.path.join(d, prefix + "*.txt")):
        t = open(f, errors="replace").read()
        m = re.search(r"Requests/sec:\s+([\d.]+)", t)
        if m:
            rps.append(float(m.group(1)))
        m2 = re.search(r"^\s+99%\s+([\d.]+)(ms|s)", t, re.M)
        if m2:
            v = float(m2.group(1)) * (1000 if m2.group(2) == "s" else 1)
            p99.append(v)
    return rps, p99

for label, prefix in (("boundary", "boundary"), ("full", "full")):
    rps, p99 = col(prefix)
    if not rps:
        print("%s: 无有效样本" % label)
        continue
    spread = (max(rps) - min(rps)) / statistics.median(rps) * 100 if len(rps) > 1 else 0
    print("%-9s n=%d  rps 中位数=%.0f  min=%.0f max=%.0f  波动=%.1f%%   P99 中位数=%.0fms" % (
        label, len(rps), statistics.median(rps), min(rps), max(rps), spread,
        statistics.median(p99) if p99 else -1))
PY
