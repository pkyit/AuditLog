#!/bin/bash
# 在同一台服务器上验证 Servlet(Tomcat) 环境的端到端验收仍然通过。
# 目的：证明"同一份代码、同一台机器，两种 Web 环境都能工作"。
set -u
cd /root/audit-log || exit 1

echo "=== 构建 classpath ==="
mvn -B -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -pl audit-log-samples 2>&1 | tail -2

CP="audit-log-samples/target/classes:$(cat audit-log-samples/target/cp.txt)"
rm -rf audit-log-samples/logs logs demo-journal

echo "=== 运行 Servlet 端到端验收 ==="
timeout 300 java -Dfile.encoding=UTF-8 \
  -Dspring.profiles.active=demo \
  -Daudit.demo.exit=true \
  -cp "$CP" \
  io.github.pkyit.auditlog.samples.AuditLogSampleApplication 2>&1 \
  | grep -E 'Tomcat started|Netty started|结果：|退出码|APPLICATION FAILED' | head -8
echo "=== 完成 ==="
