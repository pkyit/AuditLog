#!/bin/bash
# 清理 102 上的压测痕迹（**只删我自己放的文件**，不动 Jenkins/Superset/docker）
# wrk 保留：它是一个放在 /usr/local/bin 的独立二进制，不影响任何服务。
set -u
echo "=== 删除我放上去的文件 ==="
for f in /root/wrk-4.2.0.tar.gz /root/install-wrk.sh /root/check-102.sh /root/recon.sh \
         /root/wrk-A-full-default.txt /root/wrk-B-spill.txt /root/wrk-C-async.txt; do
  [ -e "$f" ] && rm -f "$f" && echo "  已删 $f"
done
rm -rf /root/wrk-4.2.0 /opt/wrk-src /opt/wrk.tar.gz 2>/dev/null && echo "  已删编译目录"
rm -f /tmp/wrk-build.log

echo
echo "=== 确认没有残留我起的进程 ==="
pgrep -af '[w]rk -t' || echo "  无 wrk 进程在跑"

echo
echo "=== 确认生产服务未受影响 ==="
docker ps --format '{{.Names}}: {{.Status}}' | head -8
echo
uptime
echo
echo "=== wrk 保留情况 ==="
ls -l /usr/local/bin/wrk 2>/dev/null && /usr/local/bin/wrk --version 2>&1 | head -2
