#!/bin/bash
# 核查 GitHub Wiki 与 Actions 的可用状态。
# 说明：本机到 api.github.com 与 repo1 的连接不稳定，服务器侧 curl 正常，故在服务器侧执行。
set -u
TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"

echo "=== 仓库能力开关 ==="
curl -sS --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); print('  has_wiki     =', d.get('has_wiki')); print('  has_actions  =', d.get('has_actions')); print('  default_branch =', d.get('default_branch')); print('  pushed_at    =', d.get('pushed_at'))"

echo
echo "=== Wiki 独立 git 仓库是否已生成 ==="
code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 25 "https://github.com/$REPO/wiki" 2>/dev/null)
echo "  wiki 页面 HTTP $code"

echo
echo "=== Release 是否已存在 ==="
curl -sS --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/releases" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); [print('  tag=%s  名称=%s  草稿=%s' % (r.get('tag_name'), r.get('name'), r.get('draft'))) for r in d]"

echo
echo "=== 已推送的 tag ==="
curl -sS --max-time 30 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/tags" \
  | python3 -c "import json,sys; d=json.load(sys.stdin); [print('  ', t.get('name')) for t in d]"
