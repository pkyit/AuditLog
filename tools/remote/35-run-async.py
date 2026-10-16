#!/usr/bin/env python3
"""异步审计正确性验证（全 Python 驱动，避免 bash 后台作业/管道的行为差异）。

为什么要用 Python 而不是 bash：
  * bash 的 `cmd &` + `wait` 在非交互式、输出重定向的环境下行为不可靠
    （实测脚本会卡在 do_wait，且看不到任何进度）；
  * 管道（`| tail`）不流式落盘，一旦卡住就完全失明；
  * Python 用线程池做并发、用 requests 直接拿状态码，全程可控可观测。

断言与 assert-async 逻辑一致，但这里自己发请求、自己核对，便于定位问题。
"""
import collections
import concurrent.futures
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

JAR = "/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar"
PORT = 19400
BASE = "http://127.0.0.1:%d" % PORT
DIR = "/root/async-test"
AUDIT_DIR = DIR + "/audit"


def http(path, method="GET", body=None, timeout=25):
    """返回 (状态码, 文本)。4xx/5xx 也要拿到状态码，不能抛异常了事。"""
    url = BASE + path
    data = body.encode() if body else None
    # 每次调用都记一条：用于区分"服务端多记了一条"与"客户端多发了一次"
    CALL_LOG.append("%s %s" % (method, path))
    request = urllib.request.Request(url, data=data, method=method)
    if body:
        request.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, "EXC:%s" % error


# 记录本进程真正发出过多少次请求（线程安全）
CALL_LOG = []
_CALL_LOCK = __import__("threading").Lock()


def main():
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(AUDIT_DIR, exist_ok=True)
    os.makedirs(DIR + "/journal", exist_ok=True)

    app_log = open(DIR + "/app.log", "w")
    process = subprocess.Popen(
        ["java", "-Xms512m", "-Xmx512m",
         "-Dspring.profiles.active=bench",
         "-Dserver.port=%d" % PORT,
         "-Daudit.log.store.type=json-file",
         "-Daudit.log.store.directory=" + AUDIT_DIR,
         "-Daudit.log.journal.directory=" + DIR + "/journal",
         "-jar", JAR],
        stdout=app_log, stderr=subprocess.STDOUT)

    try:
        ready = False
        for _ in range(120):
            if http("/actuator/health", timeout=2)[0] == 200:
                ready = True
                break
            if process.poll() is not None:
                print("!! 应用启动即退出，见 %s/app.log" % DIR)
                return 1
            time.sleep(1)
        if not ready:
            print("!! 应用未在 120s 内就绪")
            return 1
        print("应用就绪 pid=%d" % process.pid)

        print()
        print("=== 顺序发起异步请求 ===")
        calls = [
            ("callable-fast", "/async/callable?delayMillis=5"),
            ("callable-slow", "/async/callable?delayMillis=800"),
            ("callable-annotated", "/async/callable-annotated?delayMillis=100"),
            ("callable-error", "/async/callable-error"),
            ("callable-500", "/async/callable-500"),
            ("callable-404", "/async/callable-404"),
            ("deferred-fast", "/async/deferred?delayMillis=5&httpStatus=200"),
            ("deferred-slow", "/async/deferred?delayMillis=800&httpStatus=200"),
            ("deferred-500", "/async/deferred?delayMillis=100&httpStatus=500"),
            ("deferred-timeout", "/async/deferred-timeout"),
            ("streaming", "/async/streaming?chunks=3"),
            ("future", "/async/future?delayMillis=120"),
            ("slow", "/async/slow"),
        ]
        for name, path in calls:
            status, _ = http(path)
            print("  %-20s HTTP %s" % (name, status))

        status, _ = http("/async/post-callable", method="POST",
                         body='{"orderId":"A-1","password":"should-be-masked"}')
        print("  %-20s HTTP %s" % ("post-callable(POST)", status))

        status, _ = http("/async/post-annotated", method="POST",
                         body='{"orderId":"A-1","password":"should-be-masked"}')
        print("  %-20s HTTP %s" % ("post-annotated(POST)", status))

        print()
        print("=== 并发 12 次异步 ===")
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            futures = [pool.submit(http, "/async/callable?delayMillis=60") for _ in range(12)]
            codes = [f.result()[0] for f in futures]
        print("  状态码:", " ".join(str(c) for c in codes))

        print()
        print("=== 等待异步收尾并落盘 ===")
        for _ in range(20):
            time.sleep(1)
            records = load_records()
            if len(records) >= 28:
                break
        time.sleep(2)

        return assert_all()
    finally:
        process.send_signal(signal.SIGTERM)
        try:
            process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            process.kill()
        app_log.close()


def load_records():
    import glob
    records = []
    for path in sorted(glob.glob(AUDIT_DIR + "/*.jsonl")):
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                line = line.strip()
                if line:
                    records.append(json.loads(line))
    return records


def assert_all():
    records = load_records()
    print("审计记录总数 =", len(records))
    by_uri = collections.defaultdict(list)
    for record in records:
        by_uri[record.get("requestUri")].append(record)

    passed, failed = [], []

    def check(ok, message):
        (passed if ok else failed).append(message)
        print(("  [通过] " if ok else "  [!! 失败] ") + message)

    expected = {
        "/async/callable": 13,
        "/async/callable-annotated": 1,
        "/async/callable-error": 1,
        "/async/callable-500": 1,
        "/async/callable-404": 1,
        "/async/deferred": 3,
        "/async/deferred-timeout": 1,
        "/async/streaming": 1,
        "/async/future": 1,
        "/async/post-callable": 1,
        "/async/post-annotated": 1,
        "/async/slow": 1,
    }

    print()
    print("=== 1) 逐 URI 对账：记录数必须等于客户端成功次数 ===")
    print("  （用客户端实测次数作为权威期望，避免把测试自身的重试算成框架缺陷）")
    client_calls = collections.Counter()
    for entry in CALL_LOG:
        path = entry.split(" ", 1)[1]
        client_calls[path.split("?")[0]] += 1
    for uri in expected:
        got = len(by_uri.get(uri, []))
        sent = client_calls.get(uri, 0)
        check(got == sent,
              "%-28s 客户端发出 %d 次，审计记录 %d 条" % (uri, sent, got))
    extra = {u: len(v) for u, v in by_uri.items() if u not in expected}
    if extra:
        print("  [信息] 其它 URI：%s" % extra)

    print()
    print("=== 2) httpStatus 是真实状态码（不是占位 200）===")
    for record in by_uri.get("/async/callable-500", []):
        check(record.get("httpStatus") == 500,
              "callable-500 httpStatus=%s（期望 500）" % record.get("httpStatus"))
    for record in by_uri.get("/async/callable-404", []):
        check(record.get("httpStatus") == 404,
              "callable-404 httpStatus=%s（期望 404）" % record.get("httpStatus"))
    for record in by_uri.get("/async/deferred", []):
        qs = record.get("queryString") or ""
        want = 500 if "httpStatus=500" in qs else 200
        check(record.get("httpStatus") == want,
              "deferred(%s) httpStatus=%s（期望 %s）" % (qs, record.get("httpStatus"), want))

    print()
    print("=== 3) durationMillis 覆盖到异步完成（关键）===")
    for uri in ("/async/callable", "/async/deferred"):
        for record in by_uri.get(uri, []):
            qs = record.get("queryString") or ""
            if "delayMillis=800" in qs:
                got = record.get("durationMillis") or 0
                check(got >= 700, "%s(%s) duration=%sms（应 >=700）" % (uri, qs, got))
    for record in by_uri.get("/async/slow", []):
        got = record.get("durationMillis") or 0
        check(got >= 700, "/async/slow duration=%sms（应 >=700）" % got)
    for record in by_uri.get("/async/streaming", []):
        got = record.get("durationMillis") or 0
        check(got >= 100, "/async/streaming duration=%sms（应覆盖写入过程）" % got)

    print()
    print("=== 4) 异步异常如实记录 ===")
    for record in by_uri.get("/async/callable-error", []):
        check(record.get("outcome") in ("FAILURE", "INVALID"),
              "callable-error outcome=%s" % record.get("outcome"))
        check(bool((record.get("error") or {}).get("type")),
              "callable-error error.type=%s" % (record.get("error") or {}).get("type"))

    print()
    print("=== 5) DeferredResult 超时不丢记录 ===")
    check(len(by_uri.get("/async/deferred-timeout", [])) == 1,
          "deferred-timeout 产生 1 条记录")

    print()
    print("=== 6) 异步 + 请求体（语义通道）：已采集且已脱敏 ===")
    print("  说明：边界通道只记元信息、不采载荷；因此请求体必须由带 @AuditLog 的端点验证")
    for record in by_uri.get("/async/post-annotated", []):
        payload = (record.get("request") or {}).get("body") or ""
        check("orderId" in payload, "异步 POST(已标注) 请求体已采集：%s" % payload[:90])
        check("should-be-masked" not in payload, "异步 POST(已标注) 不含明文口令")
        check("******" in payload, "异步 POST(已标注) 含掩码标记")
        check(record.get("operation") is not None,
              "异步 POST(已标注) operation=%s" % record.get("operation"))
    for record in by_uri.get("/async/post-callable", []):
        # 边界通道不应采集载荷 —— 这是设计意图，不是缺陷
        body = (record.get("request") or {}).get("body")
        check(body is None or body == "",
              "未标注异步 POST 走边界通道，不采集载荷（设计如此）")

    print()
    print("=== 7) 已标注异步端点：语义字段存在 ===")
    for record in by_uri.get("/async/callable-annotated", []):
        check(record.get("operation") is not None,
              "已标注异步端点 operation=%s" % record.get("operation"))

    print()
    print("通过 %d 项，失败 %d 项" % (len(passed), len(failed)))
    for item in failed:
        print("  - " + item)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
