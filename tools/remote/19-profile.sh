#!/bin/bash
# 在负载下对"采集全开"场景做 CPU 与内存分配剖析
set -u
source /etc/profile.d/audit-java17.sh
source /root/bench-lib.sh

PROF=/opt/async-profiler/bin/asprof
OUT=/root/prof
mkdir -p "$OUT"

start_app "prof-full" "bench"
PID=$(cat /root/bench/results/app-prof-full.pid)
echo "app pid = $PID"

PAYLOAD=$(python3 -c 'print("p"*180)')
URL="${BASE}/load/full?payload=${PAYLOAD}"

echo
echo "=== 预热 20s ==="
taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d20s "$URL" > /dev/null 2>&1

echo "=== 开始 CPU 剖析（wall clock, 30s）==="
taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d45s "$URL" > /root/prof/wrk-cpu.txt 2>&1 &
WRK_PID=$!
sleep 8
"$PROF" -e cpu -d 25 -f "$OUT/cpu.html" "$PID" 2>&1 | tail -3
wait $WRK_PID 2>/dev/null
echo "--- 负载结果 ---"
grep -E 'Requests/sec|Latency ' /root/prof/wrk-cpu.txt

echo
echo "=== 开始内存分配剖析（alloc, 30s）==="
taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d45s "$URL" > /root/prof/wrk-alloc.txt 2>&1 &
WRK_PID=$!
sleep 8
"$PROF" -e alloc -d 25 -f "$OUT/alloc.html" "$PID" 2>&1 | tail -3
wait $WRK_PID 2>/dev/null
echo "--- 负载结果 ---"
grep -E 'Requests/sec|Latency ' /root/prof/wrk-alloc.txt

echo
echo "=== 启动锁剖析（lock, 20s）==="
taskset -c "$LOAD_CPUS" wrk -t4 -c200 -d30s "$URL" > /root/prof/wrk-lock.txt 2>&1 &
WRK_PID=$!
sleep 6
"$PROF" -e lock -d 18 -f "$OUT/lock.html" "$PID" 2>&1 | tail -3
wait $WRK_PID 2>/dev/null

echo
echo "=== CPU 热点 Top（文本摘要，便于远程阅读）==="
"$PROF" -e cpu -d 15 -o flat -f "$OUT/cpu.txt" "$PID" 2>&1 | tail -2
head -45 "$OUT/cpu.txt" 2>/dev/null

stop_app "prof-full"
echo
echo "=== 产物 ==="
ls -lh "$OUT"
