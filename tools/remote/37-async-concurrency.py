#!/usr/bin/env python3
"""判定：并发异步请求是否会产生多余记录（把"预期值"换成客户端实测次数）。"""
import collections
import concurrent.futures
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.request

JAR = "/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar"
PORT = 19420
BASE = "http://127.0.0.1:%d" % PORT
DIR = "/root/async-conc-test"
AUDIT_DIR = DIR + "/audit"
N = 20


def http(path, timeout=25):
    try:
        with urllib.request.urlopen(BASE + path, timeout=timeout) as response:
            return response.status
    except Exception as error:  # noqa: BLE001
        return "EXC:%s" % error


def main():
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(AUDIT_DIR, exist_ok=True)
    os.makedirs(DIR + "/journal", exist_ok=True)
    log = open(DIR + "/app.log", "w")
    process = subprocess.Popen(
        ["java", "-Xms512m", "-Xmx512m", "-Dspring.profiles.active=bench",
         "-Dserver.port=%d" % PORT,
         "-Daudit.log.store.type=json-file",
         "-Daudit.log.store.directory=" + AUDIT_DIR,
         "-Daudit.log.journal.directory=" + DIR + "/journal",
         "-jar", JAR],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(120):
            try:
                urllib.request.urlopen(BASE + "/actuator/health", timeout=2).read()
                break
            except Exception:  # noqa: BLE001
                time.sleep(1)

        print("=== 顺序 %d 次（对照组）===" % N)
        sequential_codes = [http("/async/callable?delayMillis=5") for _ in range(N)]
        print("  状态码:", " ".join(str(c) for c in sequential_codes))
        time.sleep(3)
        seq_records = count()
        print("  顺序调用后记录数 = %d（期望 %d）" % (seq_records, N))

        print()
        print("=== 并发 %d 次 ===" % N)
        with concurrent.futures.ThreadPoolExecutor(max_workers=N) as pool:
            futures = [pool.submit(http, "/async/callable?delayMillis=60") for _ in range(N)]
            codes = [f.result() for f in futures]
        print("  状态码:", " ".join(str(c) for c in codes))
        ok = sum(1 for c in codes if c == 200)
        print("  客户端成功次数 = %d" % ok)

        for _ in range(15):
            time.sleep(1)
            total = count()
            if total >= N + ok:
                break
        total = count()
        print("  记录总数 = %d（顺序 %d + 并发成功 %d = %d）" % (total, N, ok, N + ok))
        print()
        if total == N + ok:
            print("结论: 记录数 == 客户端成功次数，**没有多余记录**（并发下也恰好一条）")
            return 0
        print("结论: 记录数 != 客户端成功次数，**存在多余记录**：多出 %d 条" % (total - N - ok))
        dump()
        return 1
    finally:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
        log.close()


def load():
    records = []
    for path in sorted(glob.glob(AUDIT_DIR + "/*.jsonl")):
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    records.append(json.loads(line))
    return records


def count():
    return len([r for r in load() if r.get("requestUri") == "/async/callable"])


def dump():
    ids = collections.Counter(r.get("id") for r in load()
                              if r.get("requestUri") == "/async/callable")
    print("  按 id 统计重复：")
    for rid, times in ids.items():
        if times > 1:
            print("    !! id=%s 出现 %d 次（同一上下文被发布了多次）" % (rid, times))
    print("  唯一 id 数 = %d，记录数 = %d" % (len(ids), sum(ids.values())))


if __name__ == "__main__":
    sys.exit(main())
