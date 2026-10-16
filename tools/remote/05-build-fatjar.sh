#!/bin/bash
# 在服务器上构建可执行 fat jar
set -e
source /etc/profile.d/audit-java17.sh
cd /root/audit-log
echo "=== 构建 fat jar ==="
mvn -B -q -DskipTests -Pfat-jar -pl audit-log-samples -am package 2>&1 | tail -5
ls -lh audit-log-samples/target/audit-log-samples-1.0.0.jar
echo
echo "=== 校验可执行 ==="
java -jar audit-log-samples/target/audit-log-samples-1.0.0.jar --help 2>&1 | head -3 || true
unzip -p audit-log-samples/target/audit-log-samples-1.0.0.jar META-INF/MANIFEST.MF | grep -E 'Main-Class|Start-Class' || true
