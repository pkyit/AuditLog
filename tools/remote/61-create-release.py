#!/usr/bin/env python3
"""在服务器侧创建 GitHub Release（本机到 api.github.com 连接不稳定）。"""
import json
import os
import sys
import urllib.error
import urllib.request

TOKEN = os.environ["GH_TOKEN"]
REPO = "pkyit/AuditLog"
TAG = "v1.0.1"

with open("/root/release-notes.md", encoding="utf-8") as handle:
    body = handle.read()

payload = json.dumps({
    "tag_name": TAG,
    "name": "1.0.1 —— 真实 PostgreSQL 实测修复版",
    "body": body,
    "draft": False,
    "prerelease": False,
}).encode("utf-8")

request = urllib.request.Request(
    "https://api.github.com/repos/%s/releases" % REPO,
    data=payload, method="POST",
    headers={
        "Authorization": "Bearer %s" % TOKEN,
        "Accept": "application/vnd.github+json",
        "Content-Type": "application/json; charset=utf-8",
        "User-Agent": "audit-log-release",
    })

try:
    with urllib.request.urlopen(request, timeout=60) as response:
        data = json.load(response)
        print("已创建 Release: %s" % data.get("name"))
        print("tag: %s   草稿: %s   预发布: %s" % (data.get("tag_name"), data.get("draft"), data.get("prerelease")))
        print("页面: %s" % data.get("html_url"))
        sys.exit(0)
except urllib.error.HTTPError as error:
    print("HTTP %s" % error.code)
    print(error.read().decode("utf-8", "replace")[:800])
    sys.exit(1)
