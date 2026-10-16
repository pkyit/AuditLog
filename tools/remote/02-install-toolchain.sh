#!/bin/bash
# 安装测试所需工具链：JDK17 + Maven + 编译工具 + 压测工具
# openEuler 24.09 自带 dnf；如仓库无 wrk 则从源码编译。
set -e

echo "=== 1. 安装 JDK17 / Maven / 编译工具 ==="
dnf install -y java-17-openjdk-devel maven gcc make git tar unzip 2>&1 | tail -5

echo
echo "=== 2. 校验 ==="
java -version 2>&1 | head -2
mvn -version 2>&1 | head -1
gcc --version 2>&1 | head -1

echo
echo "=== 3. 尝试从仓库安装压测工具 ==="
for pkg in wrk httpd-tools siege; do
  if dnf install -y "$pkg" >/dev/null 2>&1; then
    echo "$pkg 安装成功"
  else
    echo "$pkg 仓库不可用（后续按需从源码编译）"
  fi
done

echo
echo "=== 4. wrk 可用性 ==="
command -v wrk && wrk --version 2>&1 | head -1 || echo "wrk 不可用"

echo
echo "=== 5. ab 可用性 ==="
command -v ab && ab -V 2>&1 | head -1 || echo "ab 不可用"

echo
echo "=== 完成 ==="
for c in java mvn gcc wrk ab; do
  command -v "$c" >/dev/null 2>&1 && echo "$c: $(command -v $c)" || echo "$c: MISSING"
done
