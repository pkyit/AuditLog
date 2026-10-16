#!/bin/bash
# 在服务器侧核对 Maven Central 上的构件是否可拉取。
# 本机到 repo1 的连接不稳定，服务器侧网络更可靠，因此用作交叉验证。
BASE="https://repo1.maven.org/maven2/io/github/pkyit"
for f in \
  audit-log-parent/1.0.1/audit-log-parent-1.0.1.pom \
  audit-log-core/1.0.1/audit-log-core-1.0.1.jar \
  audit-log-core/1.0.1/audit-log-core-1.0.1.jar.asc \
  audit-log-core/1.0.1/audit-log-core-1.0.1-sources.jar \
  audit-log-core/1.0.1/audit-log-core-1.0.1-javadoc.jar \
  audit-log-spring-boot-starter/1.0.1/audit-log-spring-boot-starter-1.0.1.jar \
  audit-log-core/1.0.0/audit-log-core-1.0.0.jar
do
  code=$(curl -sS -o /dev/null -w '%{http_code}' --max-time 30 "$BASE/$f" 2>/dev/null)
  size=$(curl -sS -o /dev/null -w '%{size_download}' --max-time 30 -r 0-0 "$BASE/$f" 2>/dev/null)
  echo "  HTTP $code  $f"
done
echo "--- audit-log-core maven-metadata.xml ---"
curl -sS --max-time 30 "$BASE/audit-log-core/maven-metadata.xml" 2>/dev/null | grep -E '<version>|<latest>|<release>' | head -10
