#!/bin/bash
# 验证 C2 修复：链状态跨重启持久化与恢复（真实应用 + 真实文件存储）
set -u
source /etc/profile.d/audit-java17.sh

JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=19300
BASE="http://127.0.0.1:${PORT}"
DIR=/root/c2
SECRET=c2-restart-secret
mkdir -p "$DIR/audit" "$DIR/journal"
rm -rf "$DIR/audit"/* "$DIR/journal"/* "$DIR/chain-state.properties"

start_app() {
  nohup taskset -c 0-1 java -Xms512m -Xmx512m \
    -Dspring.profiles.active=bench \
    -Dserver.port="$PORT" \
    -Daudit.log.store.type=json-file \
    -Daudit.log.store.directory="$DIR/audit" \
    -Daudit.log.journal.directory="$DIR/journal" \
    -Daudit.log.chain.enabled=true \
    -Daudit.log.chain.secret="$SECRET" \
    -Daudit.log.chain.state-file="$DIR/chain-state.properties" \
    -jar "$JAR" > "$DIR/app-$1.log" 2>&1 &
  echo $! > "$DIR/app.pid"
  for i in $(seq 1 90); do
    curl -s -m 2 -o /dev/null "$BASE/actuator/health" && return 0
    kill -0 "$(cat "$DIR/app.pid")" 2>/dev/null || return 1
    sleep 1
  done
  return 1
}

stop_app() {
  local pid
  pid=$(cat "$DIR/app.pid" 2>/dev/null) || return 0
  kill "$pid" 2>/dev/null
  for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
  kill -9 "$pid" 2>/dev/null
  sleep 3
}

echo "=== 第一次启动 ==="
start_app 1 || { echo "!! 启动失败"; tail -20 "$DIR/app-1.log"; exit 1; }
for i in 1 2 3 4 5; do curl -s -m 5 "$BASE/load/full?payload=run1-$i" > /dev/null; done
sleep 3
echo "--- 链状态文件 ---"
cat "$DIR/chain-state.properties" 2>/dev/null || echo "!! 未生成链状态文件"
HEAD1=$(grep '^head=' "$DIR/chain-state.properties" 2>/dev/null | cut -d= -f2)
SEQ1=$(grep '^lastDeliveredSeq=' "$DIR/chain-state.properties" 2>/dev/null | cut -d= -f2)
echo "第一次运行结束: head=${HEAD1:0:16}... seq=$SEQ1"
stop_app

echo
echo "=== 第二次启动（模拟重启）==="
start_app 2 || { echo "!! 重启失败"; tail -20 "$DIR/app-2.log"; exit 1; }
grep -E '已恢复链状态|未发现历史链状态' "$DIR/app-2.log" | tail -2
for i in 1 2 3; do curl -s -m 5 "$BASE/load/full?payload=run2-$i" > /dev/null; done
sleep 3
stop_app

echo
echo "=== 校验落盘记录：跨重启链接是否连续 ==="
python3 - "$DIR/audit" "$SECRET" <<'PY'
import glob, hashlib, hmac, json, sys
directory, secret = sys.argv[1], sys.argv[2]

def canonical(rec):
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))
    def n(v):
        return "" if v is None else str(v)
    ver = rec.get("schemaVersion") or 2
    parts = [
        "v=%s" % n(rec.get("schemaVersion")),
        "id=%s" % n(rec.get("id")),
        "seq=%s" % n(rec.get("seq")),
        "eventTime=%s" % n(rec.get("eventTime")),
        "principal=%s" % n(rec.get("principalId")),
        "principalType=%s" % n(rec.get("principalType")),
        "tenant=%s" % n(rec.get("tenantId")),
        "trace=%s" % n(rec.get("traceId")),
        "operation=%s" % n(rec.get("operation")),
        "action=%s" % n(rec.get("actionType")),
        "resource=%s#%s" % (n(rec.get("resourceType")), n(rec.get("resourceId"))),
        "method=%s" % n(rec.get("httpMethod")),
        "uri=%s" % n(rec.get("requestUri")),
        "req=%s" % pd(rec.get("request")),
        "resp=%s" % pd(rec.get("response")),
        "outcome=%s" % n(rec.get("outcome")),
        "httpStatus=%s" % n(rec.get("httpStatus")),
        "bizCode=%s" % n(rec.get("bizCode")),
        "err=%s" % n((rec.get("error") or {}).get("type")),
        "duration=%s" % n(rec.get("durationMillis")),
        "rolledBack=%s" % n(rec.get("rolledBack")).lower(),
    ]
    if ver >= 2:
        def tags(rec):
            t = rec.get("tags") or {}
            return "&".join("%s=%s" % (k, n(t[k])) for k in sorted(t))
        parts += [
            "principalName=%s" % n(rec.get("principalName")),
            "sessionId=%s" % n(rec.get("sessionId")),
            "authType=%s" % n(rec.get("authType")),
            "clientIp=%s" % n(rec.get("clientIp")),
            "userAgent=%s" % n(rec.get("userAgent")),
            "queryString=%s" % n(rec.get("queryString")),
            "serviceName=%s" % n(rec.get("serviceName")),
            "instanceId=%s" % n(rec.get("instanceId")),
            "errMsg=%s" % n((rec.get("error") or {}).get("message")),
            "tags=%s" % tags(rec),
        ]
    return "|".join(parts)

records = []
for f in sorted(glob.glob(directory + "/*.jsonl")):
    for line in open(f, encoding="utf-8"):
        line = line.strip()
        if line:
            records.append(json.loads(line))
records.sort(key=lambda r: r.get("seq") or 0)
print("落盘记录数 =", len(records))

prev = None
ok = True
for r in records:
    if not r.get("recordHash"):
        continue
    if prev is not None and prev != r.get("prevHash"):
        print("  !! seq=%s 的 prevHash 与前一条 recordHash 不一致 -> 链断裂" % r.get("seq"))
        ok = False
        break
    expect = hashlib.sha256(("%s|%s" % (r.get("prevHash"), canonical(r))).encode()).hexdigest()
    if expect != r.get("recordHash"):
        print("  !! seq=%s recordHash 重算不一致" % r.get("seq"))
        ok = False
        break
    prev = r.get("recordHash")

seqs = [r.get("seq") for r in records if r.get("seq")]
print("seq 范围 =", min(seqs), "..", max(seqs))
if len(seqs) != len(set(seqs)):
    print("  !! seq 出现重复 -> 重启后序号回退")
    ok = False
print("结论:", "跨重启链连续且可离线校验" if ok else "**链不连续**")
PY
