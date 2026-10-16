#!/bin/bash
# 第二台服务器侦察：确认可用性与规格
echo "=== 主机 ==="
hostname; cat /etc/os-release 2>/dev/null | grep -E '^(NAME|VERSION)=' ; uname -r; uname -m

echo
echo "=== CPU / 内存 ==="
nproc; grep -m1 'model name' /proc/cpuinfo | cut -d: -f2
free -m | head -2

echo
echo "=== 磁盘 ==="
df -h / | tail -1

echo
echo "=== 到被测机 10.10.10.103 的连通性 ==="
ping -c 2 -W 2 10.10.10.103 2>&1 | tail -3

echo
echo "=== 工具可用性 ==="
for t in curl wrk ab java mvn python3 gcc make tar; do
  p=$(command -v $t 2>/dev/null)
  printf '  %-8s %s\n' "$t" "${p:-MISSING}"
done

echo
echo "=== 网络出口（能否装包）==="
curl -s -m 8 -o /dev/null -w 'aliyun mirror: %{http_code}\n' https://mirrors.aliyun.com/ 2>&1 || echo "  出口受限"

echo
echo "=== 包管理器 ==="
(command -v dnf && echo dnf) || (command -v yum && echo yum) || echo "无"

echo
echo "=== 当前负载与监听端口 ==="
uptime
ss -lntp 2>/dev/null | head -10
