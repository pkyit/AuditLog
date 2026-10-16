#!/bin/bash
# 提取证据：脱敏后的审计记录 + 敏感值泄漏搜索
set -u
DIR=/root/sec/audit

echo "=== 审计文件列表 ==="
ls -lh "$DIR" 2>/dev/null || { echo "（目录不存在）"; exit 0; }

echo
echo "=== 第一条审计记录（脱敏结果证据）==="
F=$(ls -1 "$DIR"/*.jsonl 2>/dev/null | head -1)
if [ -z "$F" ]; then echo "（无审计文件）"; exit 0; fi
head -1 "$F" | python3 -c '
import json, sys
d = json.loads(sys.stdin.read())
keep = ["id","seq","principalId","principalType","tenantId","traceId","operation","actionType",
        "resourceType","resourceId","clientIp","httpMethod","requestUri","queryString",
        "outcome","httpStatus","bizCode","durationMillis","transactional","rolledBack",
        "prevHash","recordHash","signature"]
print(json.dumps({k: d.get(k) for k in keep}, ensure_ascii=False, indent=2))
print()
print("tags        =", json.dumps(d.get("tags"), ensure_ascii=False))
print("request     =", json.dumps(d.get("request"), ensure_ascii=False))
print("response    =", json.dumps(d.get("response"), ensure_ascii=False))
'

echo
echo "=== 敏感值泄漏搜索（全部应为 0）==="
for s in "SuperSecret-P@ssw0rd-2024" "110101199003071234" "13800138000" \
         "6222021234567890123" "nested-deep-secret-value" "attacker-session-value" \
         "attacker-api-key-value" "eyJhbGciOiJIUzI1NiJ9.ATTACKER.TOKEN"; do
  n=$(grep -F -h -- "$s" "$DIR"/*.jsonl 2>/dev/null | wc -l)
  printf '  %-34s 出现次数=%s\n' "$s" "$n"
done

echo
echo "=== 审计文件总行数与总大小 ==="
wc -l "$DIR"/*.jsonl 2>/dev/null | tail -1
du -sh "$DIR" 2>/dev/null
