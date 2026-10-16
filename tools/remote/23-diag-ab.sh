#!/bin/bash
# 诊断：优化后的制品为何在隔离测试中启动失败
set -u
D=/root/bench/iso
echo "=== 目录内容 ==="
ls -la "$D" 2>/dev/null

echo
echo "=== 各应用日志的最后 30 行 ==="
for f in "$D"/app-*.log; do
  [ -f "$f" ] || continue
  echo "--------- $(basename "$f") (size=$(stat -c%s "$f")) ---------"
  tail -30 "$f"
  echo
done

echo "=== 逐轮结果文件（注意空文件 = 启动失败） ==="
for f in "$D"/*.txt; do
  [ -f "$f" ] || continue
  sz=$(stat -c%s "$f")
  rps=$(grep -oE 'Requests/sec:[[:space:]]+[0-9.]+' "$f" | awk '{print $2}')
  printf '  %-16s size=%-6s rps=%s\n' "$(basename "$f")" "$sz" "${rps:-NONE}"
done
