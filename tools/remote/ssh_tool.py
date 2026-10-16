#!/usr/bin/env python3
"""轻量 SSH/SFTP 助手（供本项目的远程验证使用）。

为什么要自己写：Windows 自带的 OpenSSH 客户端不支持从环境变量或标准输入读取密码，
而目标机只提供密码认证。paramiko 已在本机可用，因此用它实现。

用法：
  python ssh_tool.py exec  "<command>"                 # 执行命令并打印输出
  python ssh_tool.py put   <local> <remote>            # 上传文件
  python ssh_tool.py get   <remote> <local>            # 下载文件
  python ssh_tool.py sudo  "<command>"                 # 需要 root 时使用

凭据通过环境变量提供，避免把密码写进脚本或提交到仓库：
  AUDIT_SSH_HOST / AUDIT_SSH_PORT / AUDIT_SSH_USER / AUDIT_SSH_PASS
"""

import os
import sys
import paramiko

HOST = os.environ.get("AUDIT_SSH_HOST", "")
PORT = int(os.environ.get("AUDIT_SSH_PORT", "22"))
USER = os.environ.get("AUDIT_SSH_USER", "root")
PASSWORD = os.environ.get("AUDIT_SSH_PASS", "")
TIMEOUT = float(os.environ.get("AUDIT_SSH_TIMEOUT", "120"))


def connect():
    if not HOST or not PASSWORD:
        sys.stderr.write("缺少 AUDIT_SSH_HOST / AUDIT_SSH_PASS 环境变量\n")
        sys.exit(2)
    client = paramiko.SSHClient()
    # 首次连接不校验主机指纹：这是受控的测试机，且没有预置 known_hosts。
    # 生产环境应当改为加载并校验 known_hosts。
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(
        hostname=HOST,
        port=PORT,
        username=USER,
        password=PASSWORD,
        timeout=30,
        banner_timeout=30,
        auth_timeout=30,
        allow_agent=False,
        look_for_keys=False,
    )
    return client


def run(command, timeout=TIMEOUT):
    client = connect()
    try:
        stdin, stdout, stderr = client.exec_command(command, timeout=timeout, get_pty=False)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        code = stdout.channel.recv_exit_status()
        if out:
            sys.stdout.write(out)
        if err:
            sys.stderr.write(err)
        return code
    finally:
        client.close()


def put(local, remote):
    client = connect()
    try:
        sftp = client.open_sftp()
        # 确保远程目录存在
        remote_dir = os.path.dirname(remote)
        if remote_dir:
            try:
                sftp.stat(remote_dir)
            except IOError:
                run_mkdirs(sftp, remote_dir)
        sftp.put(local, remote)
        sftp.close()
        sys.stdout.write("uploaded %s -> %s\n" % (local, remote))
        return 0
    finally:
        client.close()


def run_mkdirs(sftp, path):
    parts = path.strip("/").split("/")
    current = ""
    for part in parts:
        current = current + "/" + part
        try:
            sftp.stat(current)
        except IOError:
            sftp.mkdir(current)


def get(remote, local):
    client = connect()
    try:
        sftp = client.open_sftp()
        sftp.get(remote, local)
        sftp.close()
        sys.stdout.write("downloaded %s -> %s\n" % (remote, local))
        return 0
    finally:
        client.close()


def main():
    if len(sys.argv) < 2:
        sys.stderr.write(__doc__)
        return 2
    action = sys.argv[1]
    timeout = TIMEOUT
    argv = sys.argv[2:]
    # 支持 --timeout <秒>，用于长时间构建
    if "--timeout" in argv:
        idx = argv.index("--timeout")
        timeout = float(argv[idx + 1])
        del argv[idx:idx + 2]
    if action == "exec":
        return run(argv[0], timeout=timeout)
    if action == "sudo":
        # 本机本身即以 root 登录，等价于直接执行
        return run(argv[0], timeout=timeout)
    if action == "put":
        return put(argv[0], argv[1])
    if action == "get":
        return get(argv[0], argv[1])
    sys.stderr.write("未知动作: %s\n" % action)
    return 2


if __name__ == "__main__":
    sys.exit(main())
