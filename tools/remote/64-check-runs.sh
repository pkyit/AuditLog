#!/bin/bash
# 查询 GitHub Actions 运行状态。
set -u
TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"

echo "=== 最近的 workflow 运行 ==="
curl -sS --max-time 40 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=5" \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
runs = d.get('workflow_runs', [])
if not runs:
    print('  暂无运行记录')
for r in runs:
    print('  #%s  %s' % (r.get('run_number'), r.get('name')))
    print('     事件=%s  状态=%s  结论=%s' % (r.get('event'), r.get('status'), r.get('conclusion')))
    print('     分支/标签=%s  提交=%s' % (r.get('head_branch'), (r.get('head_sha') or '')[:8]))
    print('     链接=%s' % r.get('html_url'))
"
