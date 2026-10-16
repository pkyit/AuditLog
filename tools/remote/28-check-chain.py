#!/usr/bin/env python3
"""判定当前链使用的算法（HMAC 还是 SHA256），并给出正确的重算方式。"""
import glob
import hashlib
import hmac
import json
import sys

directory = sys.argv[1]
secret = sys.argv[2] if len(sys.argv) > 2 else "c2-restart-secret"


def n(v):
    return "" if v is None else str(v)


def canonical(rec):
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))

    def tags(r):
        t = r.get("tags") or {}
        return "&".join("%s=%s" % (k, n(t[k])) for k in sorted(t))

    parts = [
        "v=%s" % n(rec.get("schemaVersion")), "id=%s" % n(rec.get("id")),
        "seq=%s" % n(rec.get("seq")), "eventTime=%s" % n(rec.get("eventTime")),
        "principal=%s" % n(rec.get("principalId")), "principalType=%s" % n(rec.get("principalType")),
        "tenant=%s" % n(rec.get("tenantId")), "trace=%s" % n(rec.get("traceId")),
        "operation=%s" % n(rec.get("operation")), "action=%s" % n(rec.get("actionType")),
        "resource=%s#%s" % (n(rec.get("resourceType")), n(rec.get("resourceId"))),
        "method=%s" % n(rec.get("httpMethod")), "uri=%s" % n(rec.get("requestUri")),
        "req=%s" % pd(rec.get("request")), "resp=%s" % pd(rec.get("response")),
        "outcome=%s" % n(rec.get("outcome")), "httpStatus=%s" % n(rec.get("httpStatus")),
        "bizCode=%s" % n(rec.get("bizCode")), "err=%s" % n((rec.get("error") or {}).get("type")),
        "duration=%s" % n(rec.get("durationMillis")),
        "rolledBack=%s" % n(rec.get("rolledBack")).lower(),
        "principalName=%s" % n(rec.get("principalName")), "sessionId=%s" % n(rec.get("sessionId")),
        "authType=%s" % n(rec.get("authType")), "clientIp=%s" % n(rec.get("clientIp")),
        "userAgent=%s" % n(rec.get("userAgent")), "queryString=%s" % n(rec.get("queryString")),
        "serviceName=%s" % n(rec.get("serviceName")), "instanceId=%s" % n(rec.get("instanceId")),
        "errMsg=%s" % n((rec.get("error") or {}).get("message")), "tags=%s" % tags(rec),
    ]
    return "|".join(parts)


records = []
for f in sorted(glob.glob(directory + "/*.jsonl")):
    for line in open(f, encoding="utf-8"):
        line = line.strip()
        if line:
            records.append(json.loads(line))
records.sort(key=lambda r: r.get("seq") or 0)
rec = records[0]

canon = canonical(rec)
msg = ("%s|%s" % (rec.get("prevHash"), canon)).encode()

hmac_val = hmac.new(secret.encode(), msg, hashlib.sha256).hexdigest()
sha_val = hashlib.sha256(msg).hexdigest()
print("stored recordHash =", rec.get("recordHash"))
print("HMAC 重算         =", hmac_val, "->", "MATCH" if hmac_val == rec.get("recordHash") else "no")
print("SHA256 重算       =", sha_val, "->", "MATCH" if sha_val == rec.get("recordHash") else "no")

if rec.get("signature"):
    sig = hmac.new(secret.encode(), rec.get("recordHash").encode(), hashlib.sha256).hexdigest()
    print("signature 重算    =", sig, "->", "MATCH" if sig == rec.get("signature") else "no")
print()
print("使用的算法 =", "HMAC-SHA256" if hmac_val == rec.get("recordHash") else
      ("SHA-256" if sha_val == rec.get("recordHash") else "未知（摘要格式可能不一致）"))

# 全链校验（用判定出的算法）
use_hmac = hmac_val == rec.get("recordHash")
prev = None
ok = True
for r in records:
    if not r.get("recordHash"):
        continue
    if prev is not None and prev != r.get("prevHash"):
        print("!! seq=%s prevHash 不连续" % r.get("seq"))
        ok = False
        break
    m = ("%s|%s" % (r.get("prevHash"), canonical(r))).encode()
    expect = hmac.new(secret.encode(), m, hashlib.sha256).hexdigest() if use_hmac \
        else hashlib.sha256(m).hexdigest()
    if expect != r.get("recordHash"):
        print("!! seq=%s recordHash 重算不一致" % r.get("seq"))
        ok = False
        break
    prev = r.get("recordHash")

seqs = [r.get("seq") for r in records if r.get("seq")]
dup = len(seqs) != len(set(seqs))
print()
print("记录数=%d seq范围=%s..%s 重复=%s" % (len(records), min(seqs), max(seqs), dup))
print("结论:", "跨重启链连续、可离线校验" if (ok and not dup) else "**存在问题**")
