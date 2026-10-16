#!/bin/bash
# 启动压测（在服务器本地执行，避免 pgrep 误杀 SSH 会话自身）
set -u

# 停掉可能残留的应用进程：只匹配 java 命令行，避免匹配到本脚本自身
for pid in $(pgrep -f 'java .*samples-1.0.0.jar' 2>/dev/null); do
  [ "$pid" = "$$" ] && continue
  kill "$pid" 2>/dev/null
done
sleep 2
for pid in $(pgrep -f 'java .*samples-1.0.0.jar' 2>/dev/null); do
  kill -9 "$pid" 2>/dev/null
done

rm -rf /root/bench/results /root/bench/audit /root/bench/journal
mkdir -p /root/bench

setsid nohup bash /root/bench.sh > /root/bench-console.log 2>&1 < /dev/null &
sleep 5
echo "launched, console:"
head -5 /root/bench-console.log
