#!/bin/bash
# 只停止应用与压测客户端，**不**停止正在运行的 bench.sh 编排脚本
# （之前误杀了编排脚本，导致压测中断）
set -u
for pid in $(pgrep -f 'java .*samples-1.0.0.jar' 2>/dev/null); do
  kill "$pid" 2>/dev/null
done
for pid in $(pgrep -f 'wrk -t' 2>/dev/null); do
  kill "$pid" 2>/dev/null
done
sleep 3
for pid in $(pgrep -f 'java .*samples-1.0.0.jar' 2>/dev/null); do
  kill -9 "$pid" 2>/dev/null
done
echo "apps stopped; bench.sh still running: $(pgrep -f 'bash /root/bench.sh' | wc -l)"
ss -lntp 2>/dev/null | grep -E '18083|18085' || echo "ports free"
