#!/usr/bin/env python3
"""对比 Python 重算的 canonicalContent 与 Java 写入的 recordHash，定位差异。"""
import hashlib
import hmac
import json
import sys

SECRET = "tamper-detection-secret"
PATH = sys.argv[1] if len(sys.argv) > 1 else "/root/tamper/audit/audit-00001.jsonl"

rec = json.loads(open(PATH, encoding="utf-8").readline())


def pd(p):
    if not p or not p.get("body"):
        return ""
    return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))


def nulls(v):
    return "" if v is None else str(v)


fields = [
    ("v", rec.get("schemaVersion")),
    ("id", nulls(rec.get("id"))),
    ("seq", nulls(rec.get("seq"))),
    ("eventTime", nulls(rec.get("eventTime"))),
    ("principal", nulls(rec.get("principalId"))),
    ("principalType", nulls(rec.get("principalType"))),
    ("tenant", nulls(rec.get("tenantId"))),
    ("trace", nulls(rec.get("traceId"))),
    ("operation", nulls(rec.get("operation"))),
    ("action", nulls(rec.get("actionType"))),
    ("resource", "%s#%s" % (nulls(rec.get("resourceType")), nulls(rec.get("resourceId")))),
    ("method", nulls(rec.get("httpMethod"))),
    ("uri", nulls(rec.get("requestUri"))),
    ("req", pd(rec.get("request"))),
    ("resp", pd(rec.get("response"))),
    ("outcome", nulls(rec.get("outcome"))),
    ("httpStatus", nulls(rec.get("httpStatus"))),
    ("bizCode", nulls(rec.get("bizCode"))),
    ("err", nulls((rec.get("error") or {}).get("type"))),
    ("duration", nulls(rec.get("durationMillis"))),
    ("rolledBack", nulls(rec.get("rolledBack")).lower()),
]

canonical = "|".join("%s=%s" % (k, v) for k, v in fields)

print("=== 记录原始 JSON ===")
print(json.dumps(rec, ensure_ascii=False, indent=2)[:2000])
print()
print("=== Python 重算的 canonicalContent ===")
print(canonical)
print()
print("prevHash(JSON)      =", rec.get("prevHash"))
print("recordHash(JSON)    =", rec.get("recordHash"))
expect = hmac.new(SECRET.encode(), ("%s|%s" % (rec.get("prevHash"), canonical)).encode(),
                  hashlib.sha256).hexdigest()
print("recordHash(Python)  =", expect)
print("match               =", expect == rec.get("recordHash"))
print()
print("=== 注意 schemaVersion 等字段是否出现在 JSON 里（缺失即说明编解码未保留）===")
for key in ("schemaVersion", "bizCode", "bizSuccess", "zoneId", "receivedTime", "instanceId",
            "serviceName", "sessionId", "authType", "error", "queryString"):
    print("  %-16s = %r" % (key, rec.get(key)))
