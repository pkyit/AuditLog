#!/bin/bash
# 验证 C1 报告：请求体是否被审计过滤器截断，从而破坏业务功能
#
# 方法：业务接口回显"它真正收到的字节数 + SHA-256"。
# 客户端发送已知长度的报文并自行计算 SHA-256，两者对比：
#   - 一致        -> 业务收到完整报文，无缺陷
#   - 长度=65536  -> 业务被截断（64KB 采集上限），缺陷确认
set -u
source /etc/profile.d/audit-java17.sh

JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT="${PORT:-19200}"
BASE="http://127.0.0.1:${PORT}"
OUT=/root/c1
mkdir -p "$OUT"

# 用 demo profile（该 profile 下 boundary.max-cached-body-bytes 保持默认 64KB）
nohup taskset -c 0-1 java -Xms512m -Xmx512m \
  -Dspring.profiles.active=demo -Dserver.port="$PORT" \
  -jar "$JAR" > "$OUT/app.log" 2>&1 &
PID=$!

for i in $(seq 1 60); do
  curl -s -m 2 -o /dev/null "$BASE/actuator/health" && break
  kill -0 "$PID" 2>/dev/null || { echo "!! 应用启动失败"; tail -20 "$OUT/app.log"; exit 1; }
  sleep 1
done
echo "应用就绪 (pid=$PID) 端口=$PORT"
echo

echo "=== 采集上限配置 ==="
grep -i 'maxCachedBodyBytes' /root/audit-log/audit-log-spring-boot-starter/src/main/java/io/github/pkyit/auditlog/spring/properties/AuditLogProperties.java | head -2

echo
echo "=== 逐档测试：业务侧收到的字节数 vs 发送的字节数 ==="
printf '%-12s %-14s %-14s %-8s %s\n' "发送字节" "业务收到" "SHA一致" "HTTP" "结论"
for SIZE in 1000 60000 65536 70000 200000 1000000; do
  # 生成确定性的定长 JSON 载荷
  python3 -c "
import json,sys
n=$SIZE
# 构造恰好 n 字节的 JSON: {\"data\":\"xxx...\"}
prefix='{\"data\":\"'
suffix='\"}'
fill=n-len(prefix)-len(suffix)
if fill < 0: fill = 0
sys.stdout.write(prefix + ('x'*fill) + suffix)
" > "$OUT/payload.json"
  ACTUAL_SIZE=$(stat -c%s "$OUT/payload.json")
  SENT_SHA=$(sha256sum "$OUT/payload.json" | awk '{print $1}')

  RESP=$(curl -s -m 30 -X POST -H 'Content-Type: application/json' \
         --data-binary @"$OUT/payload.json" -w '\n%{http_code}' "$BASE/load/echo" 2>/dev/null)
  CODE=$(echo "$RESP" | tail -1)
  BODY=$(echo "$RESP" | head -n -1)
  RECV_LEN=$(echo "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("receivedLength"))' 2>/dev/null || echo "?")
  RECV_SHA=$(echo "$BODY" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("sha256"))' 2>/dev/null || echo "?")

  if [ "$SENT_SHA" = "$RECV_SHA" ]; then SHAOK="是"; else SHAOK="**否**"; fi
  if [ "$RECV_LEN" = "$ACTUAL_SIZE" ]; then VERDICT="完整"; else VERDICT="**被截断**"; fi

  printf '%-12s %-14s %-14s %-8s %s\n' "$ACTUAL_SIZE" "${RECV_LEN:-?}" "$SHAOK" "$CODE" "$VERDICT"
done

echo
echo "=== 结论判定 ==="
echo "若大报文档位出现「被截断 / SHA 不一致」，则 C1 缺陷成立。"
echo
echo "=== 审计侧是否标记了 truncated ==="
F=$(ls -1 /root/logs/audit/*.jsonl 2>/dev/null | head -1)
if [ -n "$F" ]; then
  tail -3 "$F" | python3 -c '
import json,sys
for line in sys.stdin:
    line=line.strip()
    if not line: continue
    r=json.loads(line)
    req=(r.get("request") or {})
    print("  uri=%-14s originalSize=%-8s truncated=%s" % (
        r.get("requestUri"), req.get("originalSize"), req.get("truncated")))
' 2>/dev/null || echo "  (解析失败)"
else
  echo "  未找到审计文件"
fi

kill $PID 2>/dev/null
for i in $(seq 1 20); do kill -0 $PID 2>/dev/null || break; sleep 1; done
kill -9 $PID 2>/dev/null
echo "=== done ==="
