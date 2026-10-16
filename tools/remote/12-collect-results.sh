#!/bin/bash
# 汇总压测结果，输出一张对照表
set -u
R=/root/bench/results

echo "================================================================================"
echo " 审计框架性能对照结果（openEuler 24.09 / 6C8G / JDK17 / G1 / 4核应用 + 2核压测）"
echo " 压测参数: wrk -t4 -c200 -d60s --latency；预热 20s；应用堆 1G"
echo "================================================================================"
printf '%-16s %12s %10s %10s %10s %12s %10s\n' "场景" "Requests/s" "P50" "P75" "P99" "传输/秒" "非2xx"
echo "--------------------------------------------------------------------------------"

for f in "$R"/wrk-*.txt; do
  [ -f "$f" ] || continue
  name=$(basename "$f" .txt | sed 's/^wrk-//')
  rps=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
  p50=$(awk '/^[[:space:]]+50%/{print $2}' "$f")
  p75=$(awk '/^[[:space:]]+75%/{print $2}' "$f")
  p99=$(awk '/^[[:space:]]+99%/{print $2}' "$f")
  tr=$(grep -oE 'Transfer/sec:[[:space:]]+[0-9.]+[KMG]?B' "$f" | awk '{print $2}')
  non2xx=$(grep -oE 'Non-2xx or 3xx responses:[[:space:]]+[0-9]+' "$f" | awk '{print $NF}')
  [ -z "$rps" ] && rps="n/a"
  [ -z "$non2xx" ] && non2xx="0"
  printf '%-16s %12s %10s %10s %10s %12s %10s\n' "$name" "$rps" "${p50:-n/a}" "${p75:-n/a}" "${p99:-n/a}" "${tr:-n/a}" "$non2xx"
done

echo
echo "================================================================================"
echo " 审计链路自身指标（衡量是否靠丢数据换吞吐）"
echo "================================================================================"
printf '%-16s %12s %12s %12s %12s %10s\n' "场景" "collect" "delivered" "queueDepth" "lastSeq" "dropped"
echo "--------------------------------------------------------------------------------"
for f in "$R"/metrics-*.json; do
  [ -f "$f" ] || continue
  name=$(basename "$f" .json | sed 's/^metrics-//')
  collect=$(grep -oE '"audit\.collect\.total":[0-9]+' "$f" | grep -oE '[0-9]+$')
  delivered=$(grep -oE '"audit\.store\.delivered":[0-9]+' "$f" | grep -oE '[0-9]+$')
  depth=$(grep -oE '"audit\.pipeline\.queue\.depth":[0-9]+' "$f" | grep -oE '[0-9]+$')
  printf '%-16s %12s %12s %12s %12s %10s\n' "$name" "${collect:-0}" "${delivered:-0}" "${depth:-0}" "-" \
    "$(grep -oE '"audit\.pipeline\.dropped":[0-9]+' "$f" | grep -oE '[0-9]+$' || echo 0)"
done

echo
echo "--- 各场景 status（queueDepth / droppedRecords / lastDeliveredSeq）---"
for f in "$R"/status-*.txt; do
  [ -f "$f" ] || continue
  echo "$(basename "$f"): $(tr '\n' ' ' < "$f")"
done

echo
echo "--- 落盘产物（场景 E）---"
ls -lh /root/bench/audit/ 2>/dev/null || echo "（目录已被下一次场景清理）"

echo
echo "--- GC 摘要（场景 E，最后 10 行）---"
tail -10 "$R"/gc-E-full-file.log 2>/dev/null | sed 's/^/  /' || echo "（无）"
