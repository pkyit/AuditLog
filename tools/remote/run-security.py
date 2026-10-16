#!/usr/bin/env python3
"""在服务器上以指定端口启动一个应用实例并运行安全测试。

用独立端口（18085）避免压测实例（18083）的干扰。
"""
import os
import sys
import time
import subprocess
import paramiko

HOST = "10.10.10.103"
USER = "root"
PASSWORD = "147258369Pky"
JAR = "/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar"
PORT = 18085


def ssh():
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, username=USER, password=PASSWORD, timeout=30,
                   allow_agent=False, look_for_keys=False)
    return client


def run(client, command, timeout=600):
    stdin, stdout, stderr = client.exec_command(command, timeout=timeout)
    out = stdout.read().decode("utf-8", errors="replace")
    err = stderr.read().decode("utf-8", errors="replace")
    return out, err, stdout.channel.recv_exit_status()


def main():
    client = ssh()
    try:
        print("=== 启动安全测试实例 (port %d) ===" % PORT, flush=True)
        cmd = (
            "mkdir -p /root/sec/journal /root/sec/audit; "
            "nohup taskset -c 0-1 java -Xms512m -Xmx512m "
            "-Dspring.profiles.active=bench -Dserver.port=%d "
            "-Daudit.log.store.type=json-file -Daudit.log.store.directory=/root/sec/audit "
            "-Daudit.log.journal.directory=/root/sec/journal "
            "-jar %s > /root/sec/app.log 2>&1 < /dev/null & echo started"
        ) % (PORT, JAR)
        out, err, _ = run(client, cmd)
        print(out.strip() or err.strip(), flush=True)

        # 等待就绪
        for i in range(60):
            out, _, _ = run(client, "curl -s -m 2 -o /dev/null -w '%%{http_code}' http://127.0.0.1:%d/actuator/health" % PORT)
            if out.strip() == "200":
                print("实例已就绪 (%ds)" % i, flush=True)
                break
            time.sleep(1)
        else:
            print("!! 实例未就绪，日志尾部：", flush=True)
            out, _, _ = run(client, "tail -30 /root/sec/app.log")
            print(out, flush=True)
            return 1

        # 运行安全测试脚本：通过环境变量指定目标端口（不要用 sed 改写 URL）
        print("=== 执行安全测试 (SEC_BASE=http://127.0.0.1:%d) ===" % PORT, flush=True)
        out, err, code = run(
            client,
            "SEC_BASE=http://127.0.0.1:%d bash /root/security.sh" % PORT,
            timeout=900)
        print(out)
        if err.strip():
            print("--- stderr ---")
            print(err)

        # 拉取审计产物做本地复核
        print("=== 清理实例 ===", flush=True)
        run(client, "pkill -f 'server.port=%d'" % PORT)
        return code
    finally:
        client.close()


if __name__ == "__main__":
    sys.exit(main())
