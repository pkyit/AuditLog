#!/bin/bash
# 冒烟测试：确认 bench profile 下应用能启动、端点可用、指标可读
set -u
source /etc/profile.d/audit-java17.sh
APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=18083
BASE="http://127.0.0.1:${PORT}"

mkdir -p /root/bench/journal /root/bench/audit
nohup java -Xms512m -Xmx512m -Dspring.profiles.active=bench -jar "$APP_JAR" > /root/smoke.log 2>&1 &
PID=$!
echo "pid=$PID"

for i in $(seq 1 60); do
  curl -s -m 2 -o /dev/null "${BASE}/actuator/health" && break
  sleep 1
done

echo "=== health ==="
curl -s -m 5 "${BASE}/actuator/health"; echo
echo "=== /load/plain ==="
curl -s -m 5 "${BASE}/load/plain?payload=hi" -w "\nHTTP=%{http_code}\n"
echo "=== /load/off ==="
curl -s -m 5 "${BASE}/load/off?payload=hi" -w "\nHTTP=%{http_code}\n"
echo "=== /load/full ==="
curl -s -m 5 "${BASE}/load/full?payload=hi" -w "\nHTTP=%{http_code}\n"
echo "=== /load/body (POST) ==="
curl -s -m 5 -X POST -H 'Content-Type: application/json' --data @/root/bench/payload.json "${BASE}/load/body" -w "\nHTTP=%{http_code}\n"

sleep 2
echo "=== auditLog ==="
curl -s -m 5 "${BASE}/actuator/auditLog" | head -c 900; echo
echo "=== auditLogMetrics ==="
curl -s -m 5 "${BASE}/actuator/auditLogMetrics"; echo
echo "=== 错误日志（若有） ==="
grep -iE 'ERROR|Exception' /root/smoke.log | head -10 || echo "无错误"

kill $PID 2>/dev/null
sleep 3
kill -9 $PID 2>/dev/null
echo "=== smoke 完成 ==="
