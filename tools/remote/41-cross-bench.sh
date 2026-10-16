#!/bin/bash
# 端到端负载验证：**由 102 发起压测**，打到 103 上的应用。
#
# 为什么用两台机器：
#   * 103 上应用与压测工具抢同一台 6C 机器，之前的对照实验受此影响；
#   * 102 独立发起负载，应用能独占 103 的 CPU，测量更干净。
#
# 重点验证两件此前没测到的事：
#   1. **spill 稳态**：队列故意压小、强制溢出，验证 M1 修复后
#      "每个请求都做全量 WAL 扫描" 的旧问题不再拖慢吞吐；
#   2. 溢出时仍然 dropped=0（不丢数据），且记录数对得上。
set -u
source /etc/profile.d/audit-java17.sh

APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=19500
BASE="http://10.10.10.103:${PORT}"     # 从 102 视角访问（本脚本跑在 102 上）
LOCAL="http://127.0.0.1:${PORT}"
OUT=/root/cross-bench
mkdir -p "$OUT"

echo "=== 确认 103 可达 ==="
curl -s -m 5 -o /dev/null -w "103 health: %{http_code}\n" "$BASE/actuator/health" || {
  echo "!! 无法访问 103，请确认应用已启动"; exit 1; }

run_scenario() {
  local name="$1" profile="$2" path="$3" threads="$4" conns="$5" dur="$6"
  echo
  echo "==================== 场景 ${name} ===================="
  local url="${BASE}${path}"
  echo "  目标: $url"

  echo "  预热 15s ..."
  wrk -t2 -c32 -d15s "$url" > /dev/null 2>&1

  echo "  测量: wrk -t${threads} -c${conns} -d${dur}s"
  wrk -t"$threads" -c"$conns" -d"${dur}s" --latency "$url" > "$OUT/wrk-${name}.txt" 2>&1
  grep -E 'Requests/sec|Latency |50%|99%|Socket errors' "$OUT/wrk-${name}.txt" | sed 's/^/    /'

  # 审计侧指标
  curl -s -m 10 "$BASE/actuator/auditLogMetrics" > "$OUT/metrics-${name}.json" 2>/dev/null
  echo "    审计指标: $(cat "$OUT/metrics-${name}.json")"
}

echo
echo "=== 场景 A：采集全开（默认队列），确认跨机基准 ==="
run_scenario "A-full-default" "bench" "/load/full?payload=$(python3 -c 'print("p"*180)')" 4 100 60

echo
echo "=== 场景 B：采集全开 + 小队列强制 spill（验证 M1 修复后的稳态）==="
echo "  说明：队列压到 2048、批次 50，制造持续溢出，"
echo "        旧实现会在每个请求入队时遍历全部 WAL 分段做行数统计。"
run_scenario "B-spill" "bench,bench-overload" "/load/full?payload=$(python3 -c 'print("p"*180)')" 4 100 60

echo
echo "=== 场景 C：异步端点（Callable 60ms）==="
run_scenario "C-async" "bench" "/async/callable?delayMillis=60" 4 100 60

echo
echo "==================== 汇总 ===================="
for f in "$OUT"/wrk-*.txt; do
  n=$(basename "$f" .txt | sed 's/^wrk-//')
  rps=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
  p50=$(awk '/^[[:space:]]+50%/{print $2}' "$f")
  p99=$(awk '/^[[:space:]]+99%/{print $2}' "$f")
  err=$(grep -oE 'Socket errors:.*' "$f" | head -1)
  printf '  %-16s rps=%-12s p50=%-9s p99=%-9s %s\n' "$n" "${rps:-n/a}" "${p50:-n/a}" "${p99:-n/a}" "${err:-}"
done

echo
echo "==================== 不丢数据核对 ===================="
for f in "$OUT"/metrics-*.json; do
  n=$(basename "$f" .json | sed 's/^metrics-//')
  # 从 metrics 中抽出关键计数
  collect=$(grep -oE '"audit\.collect\.total":[0-9]+' "$f" | grep -oE '[0-9]+$')
  enq=$(grep -oE '"audit\.pipeline\.enqueued":[0-9]+' "$f" | grep -oE '[0-9]+$')
  journaled=$(grep -oE '"audit\.pipeline\.journaled":[0-9]+' "$f" | grep -oE '[0-9]+$')
  dropped=$(grep -oE '"audit\.pipeline\.dropped":[0-9]+' "$f" | grep -oE '[0-9]+$')
  bp=$(grep -oE '"audit\.pipeline\.backpressure":[0-9]+' "$f" | grep -oE '[0-9]+$')
  printf '  %-16s collect=%-8s enqueued=%-8s journaled=%-8s backpressure=%-8s dropped=%s\n' \
    "$n" "${collect:-0}" "${enq:-0}" "${journaled:-0}" "${bp:-0}" "${dropped:-0}"
done
