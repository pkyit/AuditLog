#!/bin/bash
# 彻底清理：杀掉所有压测脚本、应用、wrk，确认端口释放
set -u
for pat in 'bench-realab.sh' 'bench-stable.sh' 'bench-repeat.sh' 'bench-quick.sh' 'run-scenarios.sh' 'bench.sh'; do
  for pid in $(pgrep -f "$pat" 2>/dev/null); do
    [ "$pid" = "$$" ] && continue
    kill -9 "$pid" 2>/dev/null
  done
done
for pid in $(pgrep -f 'java .*samples-1.0.0.jar' 2>/dev/null); do kill -9 "$pid" 2>/dev/null; done
for pid in $(pgrep -f 'wrk -t' 2>/dev/null); do kill -9 "$pid" 2>/dev/null; done
sleep 3
echo "=== 残留检查 ==="
pgrep -af 'bench-realab|bench-stable|bench-quick|bench-repeat|samples-1.0.0.jar|wrk -t' 2>/dev/null || echo "  无残留进程"
echo "=== 端口 ==="
ss -lntp 2>/dev/null | grep -E '18083|18085|18086' || echo "  相关端口已释放"
