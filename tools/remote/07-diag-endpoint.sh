#!/bin/bash
# 诊断 /actuator/auditLog 返回 500 的原因
set -u
source /etc/profile.d/audit-java17.sh
APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=18083
BASE="http://127.0.0.1:${PORT}"

# 准备 POST 载荷
python3 - <<'PY'
import json
body = {"orderId": "ORD-1", "password": "ShouldNotAppear", "idCard": "110101199003071234", "mobile": "13812345678"}
open("/root/bench/payload.json", "w").write(json.dumps(body))
PY

mkdir -p /root/bench/journal /root/bench/audit
# 开启 debug 以便拿到完整堆栈
nohup java -Xms512m -Xmx512m -Dspring.profiles.active=bench \
    -Dlogging.level.org.springframework.web=DEBUG \
    -jar "$APP_JAR" > /root/diag.log 2>&1 &
PID=$!
for i in $(seq 1 60); do curl -s -m 2 -o /dev/null "${BASE}/actuator/health" && break; sleep 1; done

echo "=== 触发 GET /load/full ==="
curl -s -m 5 "${BASE}/load/full?payload=abc" > /dev/null

echo "=== 调用 /actuator/auditLog ==="
curl -s -m 5 -o /root/auditlog-resp.json -w "HTTP=%{http_code}\n" "${BASE}/actuator/auditLog"
echo "--- 响应体 ---"
head -c 600 /root/auditlog-resp.json; echo

echo
echo "=== POST /load/body（含敏感字段） ==="
curl -s -m 5 -X POST -H 'Content-Type: application/json' --data-binary @/root/bench/payload.json \
    "${BASE}/load/body" -w "\nHTTP=%{http_code}\n"

sleep 2
echo
echo "=== 相关异常堆栈 ==="
grep -n -A 25 'NullPointerException' /root/diag.log | head -60

kill $PID 2>/dev/null; sleep 3; kill -9 $PID 2>/dev/null
echo "=== done ==="
