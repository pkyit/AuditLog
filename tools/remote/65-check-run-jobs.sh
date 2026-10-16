#!/bin/bash
# 查询某次 workflow 运行中各任务的结论，并打印失败步骤的日志尾部。
set -u
TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"
RUN_ID="${1:?用法: check-run-jobs.sh <run_id>}"

echo "=== 任务结论 ==="
curl -sS --max-time 40 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/jobs" \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
for j in d.get('jobs', []):
    print('  任务: %s   结论: %s' % (j.get('name'), j.get('conclusion')))
    for s in j.get('steps', []):
        mark = 'OK ' if s.get('conclusion') == 'success' else ('SKIP' if s.get('conclusion') == 'skipped' else str(s.get('conclusion')))
        print('      [%s] %s' % (mark, s.get('name')))
    print()
"

echo "=== 失败任务的日志尾部 ==="
for job_id in $(curl -sS --max-time 40 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/actions/runs/$RUN_ID/jobs" \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
for j in d.get('jobs', []):
    if j.get('conclusion') == 'failure':
        print(j.get('id'))
"); do
  echo "--- job $job_id ---"
  curl -sS -L --max-time 60 \
    -H "Authorization: Bearer $TOKEN" \
    -H "Accept: application/vnd.github+json" \
    -H "User-Agent: audit-log" \
    "https://api.github.com/repos/$REPO/actions/jobs/$job_id/logs" \
    | tail -40
done
