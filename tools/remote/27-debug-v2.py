#!/usr/bin/env python3
"""比对应用输出的 canonicalContent 与校验器重算的 v2 规范化内容（逐字节）。"""
import glob
import hashlib
import hmac
import json
import sys

SECRET = sys.argv[2] if len(sys.argv) > 2 else "c2-restart-secret"
directory = sys.argv[1]


def canonical(rec):
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))

    def n(v):
        return "" if v is None else str(v)

    def tags(r):
        t = r.get("tags") or {}
        return "&".join("%s=%s" % (k, n(t[k])) for k in sorted(t))

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

rec = records[0]
canon = canonical(rec)
expect = hashlib.sha256(("%s|%s" % (rec.get("prevHash"), canon)).encode()).hexdigest()
print("schemaVersion =", rec.get("schemaVersion"))
print("stored recordHash =", rec.get("recordHash"))
print("python recomputed =", expect)
print("match =", expect == rec.get("recordHash"))
print()
print("python canonical =", repr(canon))
print()
print("=== 记录中参与/可能参与摘要的字段 ===")
for key in ("id", "seq", "eventTime", "principalId", "principalName", "principalType", "tenantId",
            "sessionId", "authType", "traceId", "operation", "actionType", "resourceType",
            "resourceId", "clientIp", "userAgent", "httpMethod", "requestUri", "queryString",
            "serviceName", "instanceId", "outcome", "httpStatus", "bizCode", "durationMillis",
            "rolledBack", "prevHash", "recordHash", "signature"):
    print("  %-16s = %r" % (key, rec.get(key)))
print("  %-16s = %r" % ("tags", rec.get("tags")))
print("  %-16s = %r" % ("error", rec.get("error")))
print("  %-16s = %r" % ("request", rec.get("request")))
print("  %-16s = %r" % ("response", rec.get("response")))
