#!/usr/bin/env python3
"""独立重算哈希并与框架写入的 recordHash 比对（对首条记录，prevHash=GENESIS）。"""
import hashlib
import json
import sys

SECRET = sys.argv[2] if len(sys.argv) > 2 else "tamper-detection-secret"


def canonical(rec):
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))

    def n(v):
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
        "req=%s" % pd(rec.get("request")),
        "resp=%s" % pd(rec.get("response")),
        "outcome=%s" % n(rec.get("outcome")),
        "httpStatus=%s" % n(rec.get("httpStatus")),
        "bizCode=%s" % n(rec.get("bizCode")),
        "err=%s" % n((rec.get("error") or {}).get("type")),
        "duration=%s" % n(rec.get("durationMillis")),
        "rolledBack=%s" % n(rec.get("rolledBack")).lower(),
    ]
    return "|".join(parts)


path = sys.argv[1]
records = [json.loads(l) for l in open(path, encoding="utf-8") if l.strip()]
print("records =", len(records))


def record_hash(prev, canon, signed):
    """HmacAuditChain 与 Sha256AuditChain 的公式不同：前者带密钥，后者不带。"""
    msg = ("%s|%s" % (prev, canon)).encode()
    if signed:
        return hmac.new(SECRET.encode(), msg, hashlib.sha256).hexdigest()
    return hashlib.sha256(msg).hexdigest()


for i, rec in enumerate(records):
    canon = canonical(rec)
    signed = bool(rec.get("signature"))
    expect = record_hash(rec.get("prevHash"), canon, signed)
    ok = expect == rec.get("recordHash")
    print("  #%d seq=%s %s match=%s" % (
        i + 1, rec.get("seq"), "HMAC" if signed else "SHA256", ok))
    if not ok:
        print("      canon  =", repr(canon))
        print("      stored =", rec.get("recordHash"))
        print("      expect =", expect)
        break
else:
    print("全部记录独立重算一致 -> 链可从持久化内容离线校验")
