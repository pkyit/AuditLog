#!/usr/bin/env python3
"""跨机负载验证编排器：在 103 起应用，从 102 打负载。

为什么需要编排器：两个动作分属两台机器，用两份脚本手工串容易出错
（我自己在这轮就踩过"两个压测脚本同时跑、互相污染"的坑）。
这里把"起应用 → 打负载 → 采指标 → 停应用"做成一个受控循环。

用法: python cross-bench.py [场景...]
"""
import json
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import paramiko  # noqa: E402

APP_HOST = "10.10.10.103"
APP_PORT = 19500
LOAD_HOST = "10.10.10.102"
CREDS = {
    APP_HOST: ("root", "147258369Pky"),
    LOAD_HOST: ("root", "123456789"),
}
PAYLOAD = "p" * 180


def run(host, command, timeout=900):
    user, password = CREDS[host]
    client = paramiko.SSHClient()
    client.set_missing_host_key_policy(paramiko.AutoAddPolicy())
    client.connect(host, username=user, password=password, timeout=30,
                   allow_agent=False, look_for_keys=False)
    try:
        _, stdout, stderr = client.exec_command(command, timeout=timeout)
        out = stdout.read().decode("utf-8", errors="replace")
        err = stderr.read().decode("utf-8", errors="replace")
        return out, err
    finally:
        client.close()


def app_start(profile):
    out, err = run(APP_HOST,
                   "bash /root/app-control.sh start '%s'" % profile, timeout=300)
    return out, err


def app_stop():
    return run(APP_HOST, "bash /root/app-control.sh stop", timeout=180)


def load(url_path, threads, conns, duration, warmup=15, tag="run"):
    url = "http://%s:%d%s" % (APP_HOST, APP_PORT, url_path)
    command = (
        "cd /root && wrk -t2 -c32 -d%ds '%s' > /dev/null 2>&1; "
        "wrk -t%d -c%d -d%ds --latency '%s' > /root/wrk-%s.txt 2>&1; "
        "cat /root/wrk-%s.txt"
    ) % (warmup, url, threads, conns, duration, url, tag, tag)
    out, _ = run(LOAD_HOST, command, timeout=duration + warmup + 120)
    return out


def metrics():
    out, _ = run(APP_HOST, "curl -s -m 10 http://127.0.0.1:%d/actuator/auditLogMetrics" % APP_PORT,
                 timeout=60)
    return out.strip()


def parse_wrk(text):
    result = {}
    match = re.search(r"Requests/sec:\s+([\d.]+)", text)
    if match:
        result["rps"] = float(match.group(1))
    for label in ("50%", "75%", "99%"):
        match = re.search(r"^\s+%s\s+([\d.]+)(ms|s)" % re.escape(label), text, re.M)
        if match:
            value = float(match.group(1))
            result["p" + label.rstrip("%")] = value * (1000 if match.group(2) == "s" else 1)
    match = re.search(r"Socket errors:.*", text)
    if match:
        result["errors"] = match.group(0)
    return result


def metric_value(raw, key):
    match = re.search(r'"%s":(\d+)' % re.escape(key), raw)
    return int(match.group(1)) if match else 0


SCENARIOS = [
    # (名称, profile, 路径, 线程, 连接, 时长)
    ("A-full-default", "bench", "/load/full?payload=" + PAYLOAD, 4, 100, 60),
    ("B-spill", "bench,bench-overload", "/load/full?payload=" + PAYLOAD, 4, 100, 60),
    ("C-async", "bench", "/async/callable?delayMillis=60", 4, 100, 60),
]


def main():
    wanted = sys.argv[1:] or [s[0] for s in SCENARIOS]
    results = []
    for name, profile, path, threads, conns, duration in SCENARIOS:
        if name not in wanted:
            continue
        print("=" * 72)
        print("场景 %s  (profile=%s)" % (name, profile))
        print("=" * 72)
        out, err = app_start(profile)
        print("  应用: " + out.strip().replace("\n", "\n  "))
        if "已启动" not in out:
            print("  !! 启动失败: " + err.strip()[:400])
            results.append((name, None, None))
            continue
        try:
            text = load(path, threads, conns, duration, tag=name)
            parsed = parse_wrk(text)
            raw = metrics()
            counts = {k: metric_value(raw, k) for k in (
                "audit.collect.total", "audit.pipeline.enqueued", "audit.pipeline.journaled",
                "audit.pipeline.backpressure", "audit.pipeline.dropped",
                "audit.store.delivered")}
            print("  结果: %s" % json.dumps(parsed, ensure_ascii=False))
            print("  审计: %s" % json.dumps(counts, ensure_ascii=False))
            results.append((name, parsed, counts))
        finally:
            app_stop()
            print("  应用已停止")

    print()
    print("=" * 72)
    print("汇总")
    print("=" * 72)
    print("%-16s %10s %8s %8s %10s %10s %10s" % (
        "场景", "req/s", "p50", "p99", "collect", "journaled", "dropped"))
    for name, parsed, counts in results:
        if not parsed:
            print("%-16s %10s" % (name, "失败"))
            continue
        print("%-16s %10.0f %8s %8s %10d %10d %10d" % (
            name, parsed.get("rps", 0),
            "%.0fms" % parsed.get("p50", 0), "%.0fms" % parsed.get("p99", 0),
            counts["audit.collect.total"], counts["audit.pipeline.journaled"],
            counts["audit.pipeline.dropped"]))


if __name__ == "__main__":
    main()
