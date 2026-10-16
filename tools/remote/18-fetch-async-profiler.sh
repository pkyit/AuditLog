#!/bin/bash
# 下载正确的 async-profiler 发行包（排除 -debug 资源，它只含符号表）
set -u
AP_DIR=/opt/async-profiler
rm -rf "$AP_DIR"; mkdir -p "$AP_DIR"

echo "=== 列出 release 资源 ==="
curl -sL -m 30 https://api.github.com/repos/async-profiler/async-profiler/releases/latest \
  | grep -oE '"browser_download_url": *"[^"]*"' | cut -d'"' -f4 | grep -v debug | head -20

URL=$(curl -sL -m 30 https://api.github.com/repos/async-profiler/async-profiler/releases/latest \
      | grep -oE '"browser_download_url": *"[^"]*linux-x64\.tar\.gz"' | head -1 | cut -d'"' -f4)

if [ -z "$URL" ]; then
  echo "未找到 linux-x64 资源，回退到固定版本 v4.5"
  URL="https://github.com/async-profiler/async-profiler/releases/download/v4.5/async-profiler-4.5-linux-x64.tar.gz"
fi

echo "下载: $URL"
curl -sL -m 180 -o /opt/ap.tar.gz "$URL"
ls -l /opt/ap.tar.gz
tar -xzf /opt/ap.tar.gz -C "$AP_DIR" --strip-components=1
echo
echo "=== 内容 ==="
ls -la "$AP_DIR"
echo "--- bin ---"
ls -la "$AP_DIR/bin" 2>/dev/null || echo "无 bin 目录"
echo "--- lib ---"
ls -la "$AP_DIR/lib" 2>/dev/null || echo "无 lib 目录"
echo
echo "=== 可用性 ==="
if [ -x "$AP_DIR/bin/asprof" ]; then
  "$AP_DIR/bin/asprof" --version 2>&1 | head -2
  echo "OK: $AP_DIR/bin/asprof"
else
  find "$AP_DIR" -name 'asprof' -o -name 'profiler.sh' 2>/dev/null | head
fi
