#!/bin/bash
# 远程环境侦察：只读操作，不做任何修改
echo "=== OS / KERNEL ==="
uname -a
cat /etc/os-release | head -3
echo
echo "=== CPU / MEM / DISK ==="
echo "cores=$(nproc)"
free -h | head -2
df -h / | tail -1
echo
echo "=== TOOLCHAIN ==="
for c in java javac mvn gradle wrk ab siege curl wget python3 git gcc make sysbench unzip tar; do
  if command -v "$c" >/dev/null 2>&1; then
    printf '%-10s OK   %s\n' "$c" "$(command -v "$c")"
  else
    printf '%-10s MISSING\n' "$c"
  fi
done
echo
echo "=== JAVA VERSION ==="
java -version 2>&1 | head -3 || echo "no java"
echo
echo "=== LISTENING PORTS ==="
ss -lntp 2>/dev/null
echo
echo "=== EXISTING JAVA PROCESSES ==="
ps -eo pid,pcpu,pmem,etime,comm --sort=-pcpu 2>/dev/null | head -10
echo
echo "=== LOAD ==="
uptime
echo
echo "=== INTERNET CHECK ==="
if curl -s -m 8 -o /dev/null -w '%{http_code}' https://repo.maven.apache.org/maven2/ 2>/dev/null; then
  echo "  <- maven central reachable"
else
  echo "  maven central NOT reachable"
fi
if curl -s -m 8 -o /dev/null -w '%{http_code}' https://mirrors.aliyun.com/ 2>/dev/null; then
  echo "  <- aliyun mirror reachable"
else
  echo "  aliyun mirror NOT reachable"
fi
echo
echo "=== FIREWALL / SELINUX ==="
systemctl is-active firewalld 2>/dev/null || echo "firewalld: not active/absent"
getenforce 2>/dev/null || echo "selinux: not present"
echo
echo "=== ULIMIT ==="
ulimit -n
