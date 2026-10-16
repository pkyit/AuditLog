#!/usr/bin/env python3
"""对第二台服务器（10.10.10.102）的 SSH 工具。

用法与 ssh_tool.py 一致，但默认指向 102，凭据从环境变量读取：
    AUDIT_SSH2_HOST / AUDIT_SSH2_USER / AUDIT_SSH2_PASS
"""
import argparse
import os
import sys
import time

import paramiko

HOST = os.environ.get("AUDIT_SSH2_HOST", "10.10.10.102")
USER = os.environ.get("AUDIT_SSH2_USER", "root")
PASSWORD = os.environ.get("AUDIT_SSH2_PASS", "")
PORT = int(os.environ.get("AUDIT_SSH2_PORT", "22"))


def connect(timeout=30):
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(HOST, port=PORT, username=USER, password=PASSWORD,
                   timeout=timeout, allow_agent=False, look_for_keys=False)
    return client


def read_channel(channel, timeout):
    """带读超时地读取通道输出。"""
    out_chunks, err_chunks = [], []
    deadline = time.time() + timeout
    channel.settimeout(1.0)
    while True:
        if channel.recv_ready():
            out_chunks.append(channel.recv(65536))
            deadline = time.time() + timeout
        if channel.recv_stderr_ready():
            err_chunks.append(channel.recv_stderr(65536))
            deadline = time.time() + timeout
        if channel.exit_status_ready() and not channel.recv_ready() and not channel.recv_stderr_ready():
            break
        if time.time() > deadline:
            channel.close()
            break
        time.sleep(0.05)
    return (b"".join(out_chunks).decode("utf-8", "replace"),
            b"".join(err_chunks).decode("utf-8", "replace"))


def main():
    parser = argparse.ArgumentParser(description="SSH tool for 10.10.10.102")
    sub = parser.add_subparsers(dest="command", required=True)

    p_exec = sub.add_parser("exec")
    p_exec.add_argument("cmd")
    p_exec.add_argument("--timeout", type=int, default=300)

    p_put = sub.add_parser("put")
    p_put.add_argument("local")
    p_put.add_argument("remote")

    p_get = sub.add_parser("get")
    p_get.add_argument("remote")
    p_get.add_argument("local")

    args = parser.parse_args()
    client = connect()
    try:
        if args.command == "exec":
            stdin, stdout, stderr = client.exec_command(args.cmd, timeout=args.timeout)
            out, err = read_channel(stdout.channel, args.timeout)
            code = stdout.channel.recv_exit_status() if stdout.channel.exit_status_ready() else -1
            sys.stdout.write(out)
            if err:
                sys.stderr.write(err)
            return 0 if code in (0, -1) else code
        if args.command == "put":
            sftp = client.open_sftp()
            sftp.put(args.local, args.remote)
            sftp.close()
            print("uploaded %s -> %s" % (args.local, args.remote))
            return 0
        if args.command == "get":
            sftp = client.open_sftp()
            sftp.get(args.remote, args.local)
            sftp.close()
            print("downloaded %s -> %s" % (args.remote, args.local))
            return 0
    finally:
        client.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
