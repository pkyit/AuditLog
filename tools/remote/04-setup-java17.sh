#!/bin/bash
# 把默认 Java 切到 JDK 17，并配置 Maven
set -e

export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-17.0.12.7-3.oe2409.x86_64

echo "=== 切换 alternatives 到 JDK17 ==="
alternatives --set java  "$JAVA_HOME/bin/java"   2>/dev/null || true
alternatives --set javac "$JAVA_HOME/bin/javac"  2>/dev/null || true

echo "=== 写入全局 profile ==="
cat > /etc/profile.d/audit-java17.sh <<'EOF'
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-17.0.12.7-3.oe2409.x86_64
export PATH=$JAVA_HOME/bin:$PATH
EOF
chmod +x /etc/profile.d/audit-java17.sh
source /etc/profile.d/audit-java17.sh

echo
echo "=== 校验 ==="
java -version 2>&1 | head -2
javac -version 2>&1
mvn -version 2>&1 | head -3

echo
echo "=== 提高文件描述符上限（压测必需） ==="
cat > /etc/security/limits.d/99-audit-test.conf <<'EOF'
*  soft  nofile  1048576
*  hard  nofile  1048576
root soft nofile 1048576
root hard nofile 1048576
EOF
ulimit -n 1048576 2>/dev/null || echo "当前会话 ulimit 调整失败（下次登录生效）"
echo "当前 ulimit -n = $(ulimit -n)"

echo
echo "=== 关闭 swap 对延迟测量的干扰（仅本次，不改持久配置） ==="
echo "swap 总量: $(free -m | awk '/Swap/{print $2}') MB（保留不动，避免影响系统）"

echo
echo "=== 完成 ==="
