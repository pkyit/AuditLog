#!/bin/bash
# 重复运行以量化测量噪声：同一构建连续跑 N 轮，报告每轮与中位数
# 用法: bash bench-repeat.sh <标签> <轮数>
set -u
source /root/bench-lib.sh

TAG="${1:-rep}"
ROUNDS="${2:-3}"
OUT=/root/bench/repeat-${TAG}
mkdir -p "$OUT"
RESULT_DIR="$OUT"
export RESULT_DIR

PAYLOAD=$(python3 -c 'print("p"*180)')

declare -a B_RPS F_RPS B_P99 F_P99

for i in $(seq 1 "$ROUNDS"); do
  log "===== ${TAG} 第 ${i}/${ROUNDS} 轮 ====="

  # 场景一：边界采集
  start_app "b${i}" "bench" || { stop_app "b${i}"; continue; }
  taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d10s "${BASE}/load/plain?payload=hello" > /dev/null 2>&1
  taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d40s --latency "${BASE}/load/plain?payload=hello" > "$OUT/b${i}.txt" 2>&1
  stop_app "b${i}"

  # 场景二：采集全开
  start_app "f${i}" "bench" || { stop_app "f${i}"; continue; }
  taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d10s "${BASE}/load/full?payload=${PAYLOAD}" > /dev/null 2>&1
  taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d40s --latency "${BASE}/load/full?payload=${PAYLOAD}" > "$OUT/f${i}.txt" 2>&1
  stop_app "f${i}"
done

echo
echo "==================== [${TAG}] 重复测量结果 ===================="
printf '%-6s %14s %12s %14s %12s\n' "轮次" "边界 rps" "边界 P99" "全开 rps" "全开 P99"
for i in $(seq 1 "$ROUNDS"); do
  br=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$OUT/b${i}.txt" 2>/dev/null | awk '{print $2}')
  bp=$(awk '/^[[:space:]]+99%/{print $2}' "$OUT/b${i}.txt" 2>/dev/null)
  fr=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$OUT/f${i}.txt" 2>/dev/null | awk '{print $2}')
  fp=$(awk '/^[[:space:]]+99%/{print $2}' "$OUT/f${i}.txt" 2>/dev/null)
  printf '%-6s %14s %12s %14s %12s\n' "$i" "${br:-n/a}" "${bp:-n/a}" "${fr:-n/a}" "${fp:-n/a}"
done

echo
python3 - "$OUT" "$ROUNDS" <<'PY'
import glob, re, statistics, sys
out, rounds = sys.argv[1], int(sys.argv[2])
def collect(prefix):
    vals = []
    for f in sorted(glob.glob("%s/%s*.txt" % (out, prefix))):
        t = open(f, errors="replace").read()
        m = re.search(r"Requests/sec:\s+([\d.]+)", t)
        if m:
            vals.append(float(m.group(1)))
    return vals
for label, prefix in (("边界采集", "b"), ("采集全开", "f")):
    v = collect(prefix)
    if v:
        print("%s: n=%d  中位数=%.1f  min=%.1f  max=%.1f  波动=%.1f%%" % (
            label, len(v), statistics.median(v), min(v), max(v),
            (max(v) - min(v)) / statistics.median(v) * 100))
PY
