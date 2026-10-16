#!/usr/bin/env python3
"""逐字节比对：应用输出的 canonicalContent vs 校验器实现的 canonical()。"""
import json
import sys

sys.path.insert(0, "/tmp")
# 直接内联校验器里的 canonical 实现（与 tamper 脚本保持一致）
def verifier_canonical(rec):
    req = rec.get("request") or {}
    resp = rec.get("response") or {}
    def pd(p):
        if not p or not p.get("body"):
            return ""
        return "%s:%s" % (p.get("contentType", ""), p.get("body", ""))
    parts = [
        "v=%s" % rec.get("schemaVersion"),
        "id=%s" % (rec.get("id") or ""),
        "seq=%s" % rec.get("seq"),
        "eventTime=%s" % rec.get("eventTime"),
        "principal=%s" % (rec.get("principalId") or ""),
        "principalType=%s" % rec.get("principalType"),
        "tenant=%s" % (rec.get("tenantId") or ""),
        "trace=%s" % (rec.get("traceId") or ""),
        "operation=%s" % (rec.get("operation") or ""),
        "action=%s" % rec.get("actionType"),
        "resource=%s#%s" % (rec.get("resourceType") or "", rec.get("resourceId") or ""),
        "method=%s" % (rec.get("httpMethod") or ""),
        "uri=%s" % (rec.get("requestUri") or ""),
        "req=%s" % pd(req),
        "resp=%s" % pd(resp),
        "outcome=%s" % rec.get("outcome"),
        "httpStatus=%s" % rec.get("httpStatus"),
        "bizCode=%s" % rec.get("bizCode"),
        "err=%s" % ((rec.get("error") or {}).get("type") or ""),
        "duration=%s" % rec.get("durationMillis"),
        "rolledBack=%s" % str(rec.get("rolledBack")).lower(),
    ]
    return "|".join(parts)

app = json.load(open("/root/dbg/chain.json"))["first"]["canonicalContent"]
rec = json.load(open("/root/dbg/first.json"))
mine = verifier_canonical(rec)

print("app  =", repr(app))
print("mine =", repr(mine))
print("equal =", app == mine)
if app != mine:
    for i in range(min(len(app), len(mine))):
        if app[i] != mine[i]:
            print("首个差异位置 %d" % i)
            print("  app  ...", repr(app[max(0,i-50):i+50]))
            print("  mine ...", repr(mine[max(0,i-50):i+50]))
            break
    else:
        print("前缀相同，长度不同: app=%d mine=%d" % (len(app), len(mine)))
        print("  app  尾部:", repr(app[len(mine)-40:]))
        print("  mine 尾部:", repr(mine[len(app)-40:]))
