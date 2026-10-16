#!/bin/bash
# 定位"外部独立校验器与框架计算的哈希不一致"的原因：
# 把应用真正参与哈希的规范化字符串与文件中持久化的记录逐字段比对。
set -u
source /etc/profile.d/audit-java17.sh

APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=18087
BASE="http://127.0.0.1:${PORT}"
SECRET=debug-secret

rm -rf /root/dbg; mkdir -p /root/dbg/audit /root/dbg/journal
nohup java -Xms256m -Xmx256m -Dspring.profiles.active=bench -Dserver.port=$PORT \
  -Daudit.log.store.type=json-file -Daudit.log.store.directory=/root/dbg/audit \
  -Daudit.log.journal.directory=/root/dbg/journal \
  -Daudit.log.chain.enabled=true -Daudit.log.chain.secret=$SECRET \
  -jar "$APP_JAR" > /root/dbg/app.log 2>&1 &
PID=$!
for i in $(seq 1 60); do curl -s -m 2 -o /dev/null "$BASE/actuator/health" && break; sleep 1; done

curl -s -m 5 "$BASE/load/full?payload=alpha" > /dev/null
curl -s -m 5 "$BASE/load/off?payload=beta" > /dev/null
sleep 3

echo "=== 应用侧（真正参与哈希的字符串）==="
curl -s -m 10 "$BASE/debug/chain" > /root/dbg/chain.json
python3 - <<'PY'
import json
d=json.load(open("/root/dbg/chain.json"))
f=d.get("first") or {}
print("id           =", f.get("id"))
print("seq          =", f.get("seq"))
print("canonical    =", repr(f.get("canonicalContent")))
print("prevHash     =", f.get("prevHash"))
print("recordHash   =", f.get("recordHash"))
print("recomputed   =", f.get("recomputedSha256"))
PY

echo
echo "=== 文件侧（持久化后的记录）==="
F=$(ls -1 /root/dbg/audit/*.jsonl | head -1)
head -1 "$F" > /root/dbg/first.json
python3 - <<'PY'
import json
rec=json.load(open("/root/dbg/first.json"))
print("id         =", rec.get("id"))
print("seq        =", rec.get("seq"))
print("prevHash   =", rec.get("prevHash"))
print("recordHash =", rec.get("recordHash"))
print()
print("=== 逐字段对比：应用 canonical 与文件反推 canonical ===")
PY

# 用同一个 Python 函数从文件反推 canonical，并与应用给出的字符串做逐字节 diff
cat > /root/dbg/cmp.py <<'PY'
import json, sys

def canonical(rec):
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))
    def n(v):
        return "" if v is None else str(v)
    return "|".join([
        "v=%s" % rec.get("schemaVersion"),
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
    ])

app = json.load(open("/root/dbg/chain.json"))["first"]["canonicalContent"]
file_rec = json.load(open("/root/dbg/first.json"))
mine = canonical(file_rec)

print("app  =", repr(app))
print("file =", repr(mine))
print("equal =", app == mine)
if app != mine:
    a, b = app, mine
    for i in range(min(len(a), len(b))):
        if a[i] != b[i]:
            print("首个差异位置 %d:" % i)
            print("  app  ...", repr(a[max(0,i-40):i+40]))
            print("  file ...", repr(b[max(0,i-40):i+40]))
            break
    else:
        print("前缀相同，长度不同：app=%d file=%d" % (len(a), len(b)))
PY
python3 /root/dbg/cmp.py

kill $PID 2>/dev/null; sleep 3; kill -9 $PID 2>/dev/null
echo "=== done ==="
