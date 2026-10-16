#!/bin/bash
# 核对 GitHub Wiki 页面是否可访问，以及 Release 列表。
set -u
TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"

echo "=== Wiki 页面可访问性 ==="
for p in "wiki" "wiki/快速开始" "wiki/配置参考" "wiki/架构设计" "wiki/环境适配" "wiki/验证与测试" "wiki/常见问题" "wiki/发布流程" "wiki/版本记录"; do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 --get --data-urlencode "x=1" "https://github.com/$REPO/$p" 2>/dev/null)
  printf '  HTTP %s  %s\n' "$code" "$p"
done

echo
echo "=== Releases ==="
curl -sS --max-time 40 \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "User-Agent: audit-log" \
  "https://api.github.com/repos/$REPO/releases" \
  | python3 -c "
import json,sys
for r in json.load(sys.stdin):
    print('  tag=%-8s 名称=%s  草稿=%s' % (r.get('tag_name'), r.get('name'), r.get('draft')))
"
