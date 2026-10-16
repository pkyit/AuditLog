#!/bin/bash
# 用 curl 创建 GitHub Release。
# 说明：服务器上 Python urllib 到 api.github.com 的 TLS 握手会超时，
# 而 curl 正常（HTTP 200），因此这里改用 curl 发送请求。
set -u

TOKEN="${GH_TOKEN:?需要设置 GH_TOKEN}"
REPO="pkyit/AuditLog"
TAG="v1.0.1"

# 用 python 仅做 JSON 转义（不发起网络请求），把发布说明嵌入 payload
python3 - <<'PY' > /root/release-payload.json
import json
body = open('/root/release-notes.md', encoding='utf-8').read()
print(json.dumps({
    "tag_name": "v1.0.1",
    "name": "1.0.1 —— 真实 PostgreSQL 实测修复版",
    "body": body,
    "draft": False,
    "prerelease": False,
}, ensure_ascii=False))
PY

echo "payload 大小: $(wc -c < /root/release-payload.json) 字节"

response=$(curl -sS --max-time 60 -X POST \
  -H "Authorization: Bearer $TOKEN" \
  -H "Accept: application/vnd.github+json" \
  -H "Content-Type: application/json" \
  -H "User-Agent: audit-log-release" \
  --data-binary @/root/release-payload.json \
  "https://api.github.com/repos/$REPO/releases" 2>&1)

echo "$response" | python3 -c "
import json,sys
raw = sys.stdin.read()
try:
    d = json.loads(raw)
except Exception:
    print('原始响应:', raw[:600]); sys.exit(1)
if 'html_url' in d:
    print('已创建 Release:', d.get('name'))
    print('tag:', d.get('tag_name'), ' 草稿:', d.get('draft'), ' 预发布:', d.get('prerelease'))
    print('页面:', d.get('html_url'))
else:
    print('创建失败:', d.get('message'))
    for e in d.get('errors', []) or []:
        print('  -', e)
    sys.exit(1)
"
