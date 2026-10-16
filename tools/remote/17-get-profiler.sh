#!/bin/bash
# 为性能剖析准备工具：优先 async-profiler（可出火焰图），退路是 JFR（JDK 自带）
set -u
source /etc/profile.d/audit-java17.sh

echo "=== JFR 是否可用（JDK 自带）==="
java -XX:+UnlockDiagnosticVMOptions -XX:+PrintFlagsFinal -version 2>/dev/null | grep -i -E 'FlightRecorder' | head -3
jfr --version 2>&1 | head -2 || echo "jfr 命令不可用"

echo
echo "=== 尝试获取 async-profiler ==="
AP_DIR=/opt/async-profiler
if [ -x "$AP_DIR/bin/asprof" ]; then
  echo "已存在: $AP_DIR"
  "$AP_DIR/bin/asprof" --version 2>&1 | head -2
else
  mkdir -p /opt && cd /opt
  # 优先用系统包，其次下载官方 release
  if dnf install -y async-profiler >/dev/null 2>&1 && command -v asprof >/dev/null 2>&1; then
    echo "dnf 安装成功: $(command -v asprof)"
  else
    echo "dnf 无此包，尝试下载官方 release ..."
    URL=$(curl -sL -m 20 https://api.github.com/repos/async-profiler/async-profiler/releases/latest \
          | grep -oE '"browser_download_url": *"[^"]*linux-x64[^"]*\.tar\.gz"' | head -1 | cut -d'"' -f4)
    if [ -n "$URL" ]; then
      echo "下载: $URL"
      curl -sL -m 120 -o /opt/ap.tar.gz "$URL" && mkdir -p "$AP_DIR" && \
        tar -xzf /opt/ap.tar.gz -C "$AP_DIR" --strip-components=1 && \
        echo "解压完成: $(ls "$AP_DIR/bin" 2>/dev/null)"
    else
      echo "无法获得下载地址（可能被网络策略拦截）"
    fi
  fi
fi

echo
echo "=== 最终可用性 ==="
if command -v asprof >/dev/null 2>&1; then echo "asprof: $(command -v asprof)"; fi
if [ -x /opt/async-profiler/bin/asprof ]; then echo "asprof(手动安装): /opt/async-profiler/bin/asprof"; fi
echo "perf: $(command -v perf || echo MISSING)"
