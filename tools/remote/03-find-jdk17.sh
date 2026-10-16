#!/bin/bash
# 查找可用的 JDK 17（本项目要求 Java 17）
echo "=== 已安装的 Java 相关包 ==="
rpm -qa | grep -iE 'jdk|java|bisheng' | sort

echo
echo "=== 仓库中可用的 JDK 包 ==="
dnf list available 2>/dev/null | grep -iE 'jdk|java' | head -40

echo
echo "=== 尝试安装 JDK17 ==="
for pkg in java-17-openjdk java-17-openjdk-headless java-17-openjdk-devel; do
  echo "--- try $pkg ---"
  if dnf install -y "$pkg" >/dev/null 2>&1; then
    echo "  $pkg OK"
  else
    echo "  $pkg 不可用"
  fi
done

echo
echo "=== 系统中所有 java 可执行文件 ==="
ls -1 /usr/lib/jvm/ 2>/dev/null || echo "无 /usr/lib/jvm"
find / -maxdepth 5 -name 'java' -type f -perm -u+x 2>/dev/null | head -10

echo
echo "=== alternatives ==="
alternatives --list 2>/dev/null | grep -i java || echo "none"
