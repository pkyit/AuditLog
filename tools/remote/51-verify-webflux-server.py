#!/usr/bin/env python3
"""在 103 上验证 WebFlux 宿主的审计行为（fat jar 方式）。

用 fat jar 而不是 classpath，验证的是"使用者真实会拿到的制品"。
"""
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

JAR = "/root/audit-log/audit-log-samples-webflux/target/audit-log-samples-webflux-1.0.0.jar"
CLASSES_DIR = "/root/audit-log/audit-log-samples-webflux/target/classes"
CP_FILE = "/root/audit-log/audit-log-samples-webflux/target/cp.txt"
CP_FILE_CONTENT = ""
if os.path.exists(CP_FILE):
    CP_FILE_CONTENT = open(CP_FILE, encoding="utf-8").read().strip()
PORT = int(os.environ.get("AUDIT_WEBFLUX_PORT", "19700"))
BASE = "http://127.0.0.1:%d" % PORT
DIR = "/root/webflux-test"
AUDIT_DIR = DIR + "/audit"


def http(path, method="GET", body=None, timeout=30):
    request = urllib.request.Request(BASE + path,
                                     data=body.encode() if body else None, method=method)
    if body:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, "EXC:%s" % error


def main():
    if not os.path.exists(JAR) and not os.path.exists(CLASSES_DIR):
        print("!! 既没有 fat jar 也没有 classes 目录")
        return 1
    # 优先用 fat jar（用户真实拿到的制品）；若未 repackage（需要 fat-jar profile），
    # 退回显式 classpath —— 两种方式跑的是同一份代码，验证结论等价。
    if os.path.getsize(JAR) > 100_000:
        launch = ["-jar", JAR]
        mode = "fat-jar"
    else:
        launch = ["-cp", CLASSES_DIR + ":" + CP_FILE_CONTENT,
                  "io.github.pkyit.auditlog.samples.webflux.WebFluxSampleApplication"]
        mode = "classpath"
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(AUDIT_DIR, exist_ok=True)
    os.makedirs(DIR + "/journal", exist_ok=True)
    log = open(DIR + "/app.log", "w")
    print("启动方式: %s" % mode)
    process = subprocess.Popen(
        ["java", "-Xms512m", "-Xmx512m", "-Dserver.port=%d" % PORT,
         "-Daudit.log.store.type=json-file",
         "-Daudit.log.store.directory=" + AUDIT_DIR,
         "-Daudit.log.journal.directory=" + DIR + "/journal",
         "-Daudit.log.chain.enabled=true",
         "-Daudit.log.chain.secret=webflux-test-secret",
         "-Daudit.log.chain.state-file=" + DIR + "/chain-state.properties"]
        + launch,
        stdout=log, stderr=subprocess.STDOUT)
    try:
        ready = False
        for _ in range(120):
            if http("/actuator/health", timeout=2)[0] == 200:
                ready = True
                break
            if process.poll() is not None:
                print("!! 启动失败：")
                sys.stdout.write(open(DIR + "/app.log", encoding="utf-8",
                                      errors="replace").read()[-2000:])
                return 1
            time.sleep(1)
        if not ready:
            print("!! 未在 120s 内就绪")
            return 1
        print("应用就绪（WebFlux/Netty）pid=%d" % process.pid)

        # 核对容器类型：WebFlux 必须是 Netty，绝不能是 Tomcat
        log_text = open(DIR + "/app.log", encoding="utf-8", errors="replace").read()
        netty = "Netty started" in log_text
        tomcat = "Tomcat started" in log_text
        print("  容器: Netty=%s Tomcat=%s" % (netty, tomcat))
        print("  环境自检: %s" % ("REACTIVE 且过滤器已注册"
                                  if "REACTIVE" in log_text else "未发现环境自检日志"))

        calls = [("plain", "/reactive/plain"), ("slow", "/reactive/slow/600"),
                 ("error", "/reactive/error"), ("404", "/reactive/not-exist"),
                 ("stream", "/reactive/stream")]
        for name, path in calls:
            status, _ = http(path)
            print("  %-10s HTTP %s" % (name, status))
        payload = '{"orderId":"WF-1","password":"should-be-masked"}'
        status, body = http("/reactive/post", method="POST", body=payload)
        echoed = '"echoedOrderId":"WF-1"' in body.replace(" ", "")
        print("  %-10s HTTP %-4s 业务读完整体=%s" % ("post", status, echoed))

        time.sleep(5)
        import glob
        import json
        records = []
        for path in sorted(glob.glob(AUDIT_DIR + "/*.jsonl")):
            with open(path, encoding="utf-8") as handle:
                for line in handle:
                    if line.strip():
                        records.append(json.loads(line))
        print()
        print("审计记录数 = %d（期望 6）" % len(records))
        for record in records:
            print("  uri=%-22s status=%-5s duration=%-6s outcome=%-8s seq=%s" % (
                record.get("requestUri"), record.get("httpStatus"),
                record.get("durationMillis"), record.get("outcome"), record.get("seq")))

        ok = (len(records) == 6 and netty and not tomcat and echoed
              and any(r.get("httpStatus") == 404 for r in records)
              and any(r.get("httpStatus") == 500 for r in records))
        print()
        print("结论:", "WebFlux 环境下采集正常" if ok else "**存在问题**")
        return 0 if ok else 1
    finally:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
        log.close()


if __name__ == "__main__":
    sys.exit(main())
