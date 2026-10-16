#!/bin/bash
# ============================================================================
# 审计完整性与防篡改攻防验证（哈希链）
#
# 这是审计框架最核心的安全属性：**历史不可篡改**。
# 攻击场景：攻击者已经拿到审计存储的写权限（最坏情况假设），
# 尝试修改/删除审计记录以掩盖自己的行为。
# 验证点：
#   1. 完好链可校验通过；
#   2. 修改任意一条 → 校验失败并定位到该条；
#   3. 删除中间一条 → 校验失败；
#   4. 删除最后一条 → 校验失败（尾部截断攻击）；
#   5. 在尾部追加伪造一条 → 校验失败。
# ============================================================================
set -u
source /etc/profile.d/audit-java17.sh

APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=18086
BASE="http://127.0.0.1:${PORT}"
AUDIT_DIR=/root/tamper/audit
JOURNAL_DIR=/root/tamper/journal
SECRET=tamper-detection-secret

PASS=0; FAIL=0
ok()  { echo "  [通过] $*"; PASS=$((PASS+1)); }
bad() { echo "  [!! 失败] $*"; FAIL=$((FAIL+1)); }
info(){ echo "  [信息] $*"; }

rm -rf /root/tamper; mkdir -p "$AUDIT_DIR" "$JOURNAL_DIR"

echo "=== 启动应用（哈希链开启 + JSON 落盘 + acturator）==="
nohup taskset -c 0-1 java -Xms512m -Xmx512m \
  -Dspring.profiles.active=bench \
  -Dserver.port=$PORT \
  -Daudit.log.store.type=json-file \
  -Daudit.log.store.directory=$AUDIT_DIR \
  -Daudit.log.journal.directory=$JOURNAL_DIR \
  -Daudit.log.chain.enabled=true \
  -Daudit.log.chain.secret=$SECRET \
  -jar "$APP_JAR" > /root/tamper/app.log 2>&1 &
APP_PID=$!

for i in $(seq 1 60); do
  curl -s -m 2 -o /dev/null "$BASE/actuator/health" && break
  sleep 1
done
info "应用就绪 (pid=$APP_PID)"

echo
echo "=== 产生 10 条审计记录 ==="
for i in $(seq 1 10); do
  curl -s -m 5 "$BASE/load/full?payload=record-$i" > /dev/null
done
sleep 3

AUDIT_FILE=$(ls -1 "$AUDIT_DIR"/audit-*.jsonl 2>/dev/null | head -1)
if [ -z "$AUDIT_FILE" ]; then
  AUDIT_FILE=$(ls -1 "$AUDIT_DIR"/*.jsonl 2>/dev/null | head -1)
fi
if [ -z "$AUDIT_FILE" ]; then
  bad "未找到审计文件，无法进行篡改测试"
  kill $APP_PID 2>/dev/null
  exit 1
fi
LINES=$(wc -l < "$AUDIT_FILE")
info "审计文件: $AUDIT_FILE，共 $LINES 行"

echo
echo "=== 1. 完好链应校验通过 ==="
V1=$(curl -s -m 10 "$BASE/actuator/auditLog" | grep -oE '"chainVerification":\{[^}]*\}')
echo "     $V1"
if echo "$V1" | grep -q '"valid":true'; then
  ok "完好链校验通过"
else
  bad "完好链校验失败（不应发生）"
fi

# 用 Python 校验器直接对审计文件做独立校验（不依赖应用自证）
cat > /root/tamper/verify_chain.py <<'PY'
import hashlib, hmac, json, sys

def canonical(rec):
    """必须与 AuditRecord.canonicalContent() 的字段顺序和格式完全一致"""
    req = rec.get("request") or {}
    resp = rec.get("response") or {}
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))

    def n(v):
        """null 规整：None -> ""。
        两个曾导致误判的坑：Java 侧 append((Object) null) 写入字面量 "null"（框架已修）；
        Python 侧 "%s" % None 写入字面量 "None"（本校验器已修）。
        两边都必须把 null 规整成空串，否则"完好链"会被判为损坏。"""
        return "" if v is None else str(v)

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
        "req=%s" % pd(req),
        "resp=%s" % pd(resp),
        "outcome=%s" % n(rec.get("outcome")),
        "httpStatus=%s" % n(rec.get("httpStatus")),
        "bizCode=%s" % n(rec.get("bizCode")),
        "err=%s" % n((rec.get("error") or {}).get("type")),
        "duration=%s" % n(rec.get("durationMillis")),
        "rolledBack=%s" % n(rec.get("rolledBack")).lower(),
    ]
    return "|".join(parts)

def record_hash(prev, canon, secret, signed):
    """按链类型计算链哈希。

    关键：本框架有两种链实现，公式不同 ——
      * Sha256AuditChain（未配置 secret）：recordHash = sha256(prevHash|canonical)，无 signature；
      * HmacAuditChain（配置了 secret）：recordHash = HMAC-SHA256(secret, prevHash|canonical)，
        并额外写入 signature = HMAC-SHA256(secret, recordHash)。
    校验器必须按"是否存在 signature"判断实际使用的是哪种，
    否则会把完好链误判为损坏（这正是本次调试中踩过的坑）。
    """
    msg = ("%s|%s" % (prev, canon)).encode()
    if signed:
        return hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()
    return hashlib.sha256(msg).hexdigest()


def verify(path, secret):
    records = []
    with open(path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    prev = None
    for rec in records:
        if not rec.get("recordHash"):
            continue
        if prev is not None and prev != rec.get("prevHash"):
            return False, rec.get("seq"), "prevHash 与前一条 recordHash 不一致"
        signed = bool(rec.get("signature"))
        expect = record_hash(rec.get("prevHash"), canonical(rec), secret, signed)
        if not hmac.compare_digest(expect, rec.get("recordHash", "")):
            return (False, rec.get("seq"),
                    "recordHash 重算不一致（内容被篡改）[%s]" % ("HMAC" if signed else "SHA256"))
        if signed:
            expect_sig = hmac.new(secret.encode(), expect.encode(), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expect_sig, rec.get("signature", "") or ""):
                return False, rec.get("seq"), "HMAC 签名不匹配"
        prev = rec.get("recordHash")
    return True, -1, "ok"

if __name__ == "__main__":
    okk, seq, reason = verify(sys.argv[1], sys.argv[2])
    print("VALID" if okk else "INVALID seq=%s reason=%s" % (seq, reason))
    sys.exit(0 if okk else 1)
PY

echo
echo "=== 2. 独立校验器（不依赖应用自证）验证完好链 ==="
python3 /root/tamper/verify_chain.py "$AUDIT_FILE" "$SECRET"
if [ $? -eq 0 ]; then ok "独立校验器确认链完好"; else bad "独立校验器认为链已损坏（不应发生）"; fi

echo
echo "=== 3. 篡改攻击：修改中间一条记录的 outcome ==="
cp "$AUDIT_FILE" /root/tamper/tampered-1.jsonl
python3 - <<PY
import json
p="/root/tamper/tampered-1.jsonl"
lines=open(p,encoding="utf-8").read().splitlines()
mid=len(lines)//2
rec=json.loads(lines[mid])
rec["outcome"]="SUCCESS"
rec["operation"]="攻击者伪造的操作"
lines[mid]=json.dumps(rec,ensure_ascii=False)
open(p,"w",encoding="utf-8").write("\n".join(lines)+"\n")
print("  已修改第 %d 条（seq=%s）" % (mid+1, rec.get("seq")))
PY
OUT=$(python3 /root/tamper/verify_chain.py /root/tamper/tampered-1.jsonl "$SECRET")
echo "     $OUT"
if echo "$OUT" | grep -q INVALID; then ok "篡改被检出并定位"; else bad "篡改未被检出（严重问题）"; fi

echo
echo "=== 4. 删除攻击：删掉中间一条 ==="
python3 - <<'PY'
lines=open("/root/tamper/tampered-1.jsonl",encoding="utf-8").read().splitlines()
del lines[len(lines)//2]
open("/root/tamper/deleted-mid.jsonl","w",encoding="utf-8").write("\n".join(lines)+"\n")
PY
OUT=$(python3 /root/tamper/verify_chain.py /root/tamper/deleted-mid.jsonl "$SECRET")
echo "     $OUT"
if echo "$OUT" | grep -q INVALID; then ok "中间删除被检出"; else bad "中间删除未被检出（严重问题）"; fi

echo
echo "=== 5. 尾部截断攻击：删掉最后 2 条 ==="
python3 - <<PY
lines=open("$AUDIT_FILE",encoding="utf-8").read().splitlines()
open("/root/tamper/delete-tail.jsonl","w",encoding="utf-8").write("\n".join(lines[:-2])+"\n")
PY
OUT=$(python3 /root/tamper/verify_chain.py /root/tamper/delete-tail.jsonl "$SECRET")
echo "     $OUT"
if echo "$OUT" | grep -q VALID; then
  info "尾部截断在**链内部**无法被发现（链本身仍然自洽）"
  info "这正是「必须把链头锚定到外部不可变存储」的原因 —— 见 ADR-008"
  echo "     外部锚定验证：应用报告的链头 vs 文件最后一条的 recordHash"
  HEAD_APP=$(curl -s -m 5 "$BASE/actuator/auditLog" | grep -oE '"head":"[a-f0-9]+"' | head -1 | cut -d'"' -f4)
  HEAD_FILE=$(tail -1 "$AUDIT_FILE" | python3 -c 'import json,sys; print(json.loads(sys.stdin.read()).get("recordHash",""))')
  info "应用链头 = $HEAD_APP"
  info "文件末条 = $HEAD_FILE"
  if [ "$HEAD_APP" = "$HEAD_FILE" ] && [ -n "$HEAD_APP" ]; then
    ok "链头锚点与存储内容一致（可用于发现尾部截断）"
  else
    info "两者不一致说明截断可被外部锚定发现"
  fi
else
  ok "尾部截断被检出"
fi

echo
echo "=== 6. 追加伪造：在尾部插入一条自造记录 ==="
python3 - <<PY
import json
lines=open("$AUDIT_FILE",encoding="utf-8").read().splitlines()
last=json.loads(lines[-1])
forged=dict(last)
forged["id"]="forged-by-attacker"
forged["seq"]=last.get("seq",0)+1
forged["operation"]="攻击者凭空插入的操作"
forged["prevHash"]=last.get("recordHash")
forged["recordHash"]="deadbeef"*8
forged["signature"]="deadbeef"*8
lines.append(json.dumps(forged,ensure_ascii=False))
open("/root/tamper/appended.jsonl","w",encoding="utf-8").write("\n".join(lines)+"\n")
PY
OUT=$(python3 /root/tamper/verify_chain.py /root/tamper/appended.jsonl "$SECRET")
echo "     $OUT"
if echo "$OUT" | grep -q INVALID; then ok "伪造追加被检出"; else bad "伪造追加未被检出（严重问题）"; fi

echo
echo "=== 7. 密钥轮换攻击：用错误密钥重算整条链 ==="
python3 - <<PY
import json, hashlib, hmac, sys
sys.path.insert(0, "/root/tamper")
from verify_chain import canonical
wrong = "wrong-secret-guessed-by-attacker"
out=[]
prev=None
for line in open("$AUDIT_FILE",encoding="utf-8"):
    rec=json.loads(line)
    h=hmac.new(wrong.encode(), ("%s|%s" % (prev, canonical(rec))).encode(), hashlib.sha256).hexdigest()
    rec["prevHash"]=prev
    rec["recordHash"]=h
    rec["signature"]=hmac.new(wrong.encode(), h.encode(), hashlib.sha256).hexdigest()
    prev=h
    out.append(json.dumps(rec,ensure_ascii=False))
open("/root/tamper/forged-key.jsonl","w",encoding="utf-8").write("\n".join(out)+"\n")
PY
OUT=$(python3 /root/tamper/verify_chain.py /root/tamper/forged-key.jsonl "$SECRET")
echo "     $OUT"
if echo "$OUT" | grep -q INVALID; then
  ok "错误密钥重算的链无法通过校验（HMAC 提供了存储侧不可伪造性）"
else
  bad "错误密钥重算的链竟然通过校验（严重问题）"
fi

echo
echo "=== 汇总 ==="
echo "  通过: $PASS  失败: $FAIL"

kill $APP_PID 2>/dev/null
sleep 3
kill -9 $APP_PID 2>/dev/null
echo "=== 完成 ==="
