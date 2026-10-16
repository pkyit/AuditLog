#!/bin/bash
# 确认服务器上的 fat jar 里到底装的是哪一版 AuditRecord
set -u
JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
WORK=/tmp/jarcheck
rm -rf "$WORK"; mkdir -p "$WORK"

echo "=== fat jar 时间戳 ==="
ls -l --time-style=full-iso "$JAR"

echo
echo "=== 是否包含旧的 core 依赖 ==="
unzip -l "$JAR" | grep -i 'audit-log-core' || echo "(未找到 core jar)"

echo
echo "=== 抽出 core jar 里的 AuditRecord.class 并检查常量池 ==="
unzip -o -q "$JAR" 'BOOT-INF/lib/audit-log-core-*.jar' -d "$WORK" 2>/dev/null
CORE=$(ls "$WORK"/BOOT-INF/lib/audit-log-core-*.jar 2>/dev/null | head -1)
if [ -z "$CORE" ]; then echo "!! 未找到 core jar"; exit 1; fi
echo "core jar: $(basename "$CORE")"
unzip -o -q "$CORE" 'io/github/pkyit/auditlog/core/model/AuditRecord.class' -d "$WORK"
CLS="$WORK/io/github/pkyit/auditlog/core/model/AuditRecord.class"
ls -l "$CLS"

echo
echo "=== 常量池里与 null 规整相关的线索 ==="
echo "--- 是否还有 StringBuilder.append(Object) 的调用（旧实现）？ ---"
javap -p -c "$CLS" 2>/dev/null | grep -n 'canonicalContent' | head -3
echo
echo "--- canonicalContent 中 append 调用签名统计 ---"
javap -p -c "$CLS" 2>/dev/null | awk '/public java.lang.String canonicalContent/,/^$/' | grep -oE 'java/lang/StringBuilder\.append:\([^)]*\)' | sort | uniq -c
echo
echo "--- 是否引用了 nullSafe(Ljava/lang/Object;)（新实现） ---"
javap -p "$CLS" 2>/dev/null | grep -i nullsafe || echo "(未找到 nullSafe 方法声明)"
javap -p -c "$CLS" 2>/dev/null | grep -c 'nullSafe' || true
