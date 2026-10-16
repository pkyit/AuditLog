#!/bin/bash
# 手动触发 release 工作流（演练模式：完整构建、Javadoc、GPG 签名，但不上传）。
set -u
TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"

echo "=== 触发 workflow_dispatch ==="
curl -sS --max-time 40 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/actions/workflows/release.yml/dispatches" \
  -d '{"ref":"master"}' -o /dev/null -w '  HTTP %{http_code}\n'

echo "等待工作流启动..."
sleep 20
curl -sS --max-time 40 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/actions/runs?per_page=2" \
  | python3 -c "
import json,sys
d = json.load(sys.stdin)
for r in d.get('workflow_runs', []):
    print('  #%s  事件=%s  状态=%s  结论=%s' % (r.get('run_number'), r.get('event'), r.get('status'), r.get('conclusion')))
    print('     %s' % r.get('html_url'))
"
