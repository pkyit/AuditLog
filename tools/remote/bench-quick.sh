#!/bin/bash
# 可重复的前后对比基准：只测两个关键场景，缩短时长以便快速迭代
# 用法: bash bench-quick.sh <标签>
set -u
source /root/bench-lib.sh

TAG="${1:-run}"
RESULT_DIR=/root/bench/results-opt
mkdir -p "$RESULT_DIR"
RESULT_DIR_ORIG=/root/bench/results
export RESULT_DIR

PAYLOAD=$(python3 -c 'print("p"*180)')

# 覆盖结果目录，避免污染完整基线
run_one() {
  local scenario="$1" profiles="$2" path="$3" label="$4"
  log "---- [${TAG}] ${label} ----"
  start_app "$scenario" "$profiles" || { stop_app "$scenario"; return 1; }
  local url="${BASE}${path}"
  taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d10s "$url" > /dev/null 2>&1
  local out="$RESULT_DIR/wrk-${TAG}-${scenario}.txt"
  taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d30s --latency "$url" > "$out" 2>&1
  grep -E 'Requests/sec|Latency |50%|99%' "$out" | sed 's/^/      /'
  audit_metrics > "$RESULT_DIR/metrics-${TAG}-${scenario}.json" 2>/dev/null
  stop_app "$scenario"
}

run_one "boundary"  "bench"          "/load/plain?payload=hello" "边界采集（未标注注解）"
run_one "full"      "bench"          "/load/full?payload=${PAYLOAD}" "采集全开（头+体+脱敏）"

echo
echo "==== [${TAG}] 摘要 ===="
for f in "$RESULT_DIR"/wrk-${TAG}-*.txt; do
  n=$(basename "$f" .txt | sed "s/^wrk-${TAG}-//")
  rps=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
  p99=$(awk '/^[[:space:]]+99%/{print $2}' "$f")
  printf '  %-10s rps=%-12s p99=%s\n' "$n" "$rps" "$p99"
done
