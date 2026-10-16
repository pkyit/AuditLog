#!/bin/bash
# 查看跨机压测的原始参数与 102 的健康状况
echo "=== wrk 运行参数（确认与单机实验是否同口径）==="
for f in /root/wrk-A-full-default.txt /root/wrk-B-spill.txt /root/wrk-C-async.txt; do
  [ -f "$f" ] || continue
  echo "--- $(basename "$f") ---"
  head -3 "$f"
  grep -E 'Requests/sec|Latency |99%' "$f" | sed 's/^/  /'
  echo
done

echo "=== 102 当前负载（确认压测未影响 Jenkins）==="
uptime

echo
echo "=== 容器资源占用 ==="
docker stats --no-stream --format 'table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}' 2>/dev/null | head -8

echo
echo "=== Jenkins 最近日志（是否有异常）==="
docker logs --since 15m jenkins 2>&1 | grep -iE 'error|exception' | tail -3 || echo "  无错误"
