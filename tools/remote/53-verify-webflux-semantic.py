#!/usr/bin/env python3
"""在服务器上核对 Reactive 语义通道：@AuditLog 元数据是否真的写进了落盘记录。"""
import glob
import json
import os
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

JAR = "/root/audit-log/audit-log-samples-webflux/target/audit-log-samples-webflux-1.0.0.jar"
CLASSES_DIR = "/root/audit-log/audit-log-samples-webflux/target/classes"
CP_FILE = "/root/audit-log/audit-log-samples-webflux/target/cp.txt"
PORT = 19710
BASE = "http://127.0.0.1:%d" % PORT
DIR = "/root/wf-semantic"
AUDIT_DIR = DIR + "/audit"

cp = open(CP_FILE, encoding="utf-8").read().strip() if os.path.exists(CP_FILE) else ""


def http(path, method="GET"):
    request = urllib.request.Request(BASE + path, method=method)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, "EXC:%s" % error


def main():
    import shutil
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(AUDIT_DIR, exist_ok=True)
    os.makedirs(DIR + "/journal", exist_ok=True)
    log = open(DIR + "/app.log", "w")
    process = subprocess.Popen(
        ["java", "-Xms512m", "-Xmx512m", "-Dserver.port=%d" % PORT,
         "-Daudit.log.store.type=json-file",
         "-Daudit.log.store.directory=" + AUDIT_DIR,
         "-Daudit.log.journal.directory=" + DIR + "/journal",
         "-Daudit.log.chain.enabled=true",
         "-Daudit.log.chain.secret=webflux-test-secret",
         "-Daudit.log.chain.state-file=" + DIR + "/chain-state.properties",
         "-cp", CLASSES_DIR + ":" + cp,
         "io.github.pkyit.auditlog.samples.webflux.WebFluxSampleApplication"],
        stdout=log, stderr=subprocess.STDOUT)
    try:
        for _ in range(120):
            if http("/actuator/health")[0] == 200:
                break
            if process.poll() is not None:
                sys.stdout.write(open(DIR + "/app.log", encoding="utf-8",
                                      errors="replace").read()[-1500:])
                return 1
            time.sleep(1)

        status, _ = http("/reactive/annotated?orderId=ORD-9", method="POST")
        print("POST /reactive/annotated?orderId=ORD-9 -> HTTP %s" % status)
        status, _ = http("/reactive/unannotated")
        print("GET  /reactive/unannotated                -> HTTP %s" % status)
        time.sleep(5)

        records = []
        for path in sorted(glob.glob(AUDIT_DIR + "/*.jsonl")):
            with open(path, encoding="utf-8") as handle:
                for line in handle:
                    if line.strip():
                        records.append(json.loads(line))

        passed, failed = [], []

        def check(ok, message):
            (passed if ok else failed).append(message)
            print(("  [通过] " if ok else "  [!! 失败] ") + message)

        print()
        print("=== Reactive 语义通道（服务器实测）===")
        annotated = [r for r in records if r.get("requestUri") == "/reactive/annotated"]
        check(len(annotated) == 1, "annotated 记录数=%d（期望 1）" % len(annotated))
        for record in annotated:
            check(record.get("operation") == "创建反应式订单",
                  "operation=%s" % record.get("operation"))
            check(record.get("actionType") == "MUTATE", "actionType=%s" % record.get("actionType"))
            check(record.get("resourceType") == "reactive-order",
                  "resourceType=%s" % record.get("resourceType"))
            check(record.get("resourceId") == "ORD-9",
                  "resourceId=%s（按 resourceIdParam 从查询参数解析）" % record.get("resourceId"))
            check((record.get("tags") or {}).get("biz") == "webflux",
                  "自定义标签 biz=%s" % (record.get("tags") or {}).get("biz"))
        unannotated = [r for r in records if r.get("requestUri") == "/reactive/unannotated"]
        for record in unannotated:
            check(not record.get("operation"),
                  "未标注端点 operation 为空（仅边界兜底）")

        print()
        print("通过 %d 项，失败 %d 项" % (len(passed), len(failed)))
        return 1 if failed else 0
    finally:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
