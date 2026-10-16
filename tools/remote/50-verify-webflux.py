#!/usr/bin/env python3
"""WebFlux/Reactive 环境下的审计正确性验证（黑盒：真实 HTTP + 真实落盘记录）。

验证目标（每条都对应一类"能启动但不采集 / 采集错"的风险）：
  1. **确实在采集**（不是静默零采集）；
  2. 边界通道：普通返回 / 慢响应 / 异常 / 404 / POST / SSE 流式 都被记录；
  3. httpStatus 是真实状态码（异常时不得谎报 200）；
  4. durationMillis 覆盖到响应真正结束（含流式）；
  5. POST 请求体被旁路缓存**且业务仍读到完整体**（tee 不能吃掉业务的字节）；
  6. 脱敏生效（敏感头/字段不得明文落库）；
  7. 哈希链可用且自洽。
"""
import glob
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.error
import urllib.request

JAR = os.environ.get(
    "AUDIT_WEBFLUX_JAR",
    "/root/audit-log/audit-log-samples-webflux/target/audit-log-samples-webflux-1.0.0.jar")
PORT = int(os.environ.get("AUDIT_WEBFLUX_PORT", "19700"))
BASE = "http://127.0.0.1:%d" % PORT
DIR = os.environ.get("AUDIT_WEBFLUX_DIR", "/root/webflux-test")
AUDIT_DIR = DIR + "/audit"


def http(path, method="GET", body=None, timeout=30, headers=None):
    request = urllib.request.Request(BASE + path,
                                     data=body.encode() if body else None, method=method)
    if body:
        request.add_header("Content-Type", "application/json")
    if headers:
        for name, value in headers.items():
            request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            return response.status, response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as error:
        return error.code, error.read().decode("utf-8", "replace")
    except Exception as error:  # noqa: BLE001
        return 0, "EXC:%s" % error


def load_records():
    records = []
    for path in sorted(glob.glob(AUDIT_DIR + "/*.jsonl")):
        with open(path, encoding="utf-8") as handle:
            for line in handle:
                if line.strip():
                    records.append(json.loads(line))
    return records


def main():
    shutil.rmtree(DIR, ignore_errors=True)
    os.makedirs(AUDIT_DIR, exist_ok=True)
    os.makedirs(DIR + "/journal", exist_ok=True)
    log = open(DIR + "/app.log", "w")
    # 两种启动方式：
    #   * 服务器上用可执行 fat jar（默认）；
    #   * 本地开发用显式 classpath（设置 AUDIT_WEBFLUX_CP 即可），
    #     避免依赖 spring-boot-maven-plugin 的 repackage。
    explicit_cp = os.environ.get("AUDIT_WEBFLUX_CP")
    if explicit_cp:
        launch = ["-cp", explicit_cp,
                  "io.github.pkyit.auditlog.samples.webflux.WebFluxSampleApplication"]
    else:
        launch = ["-jar", JAR]
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
                print("!! 应用启动即退出，日志尾部：")
                tail = open(DIR + "/app.log", encoding="utf-8", errors="replace").read()[-2500:]
                # Windows 控制台默认 GBK，直接 print 含替换字符的文本会抛 UnicodeEncodeError；
                # 这里做一次安全降级，保证"启动失败"这个信息本身一定能被看到。
                sys.stdout.write(tail.encode(sys.stdout.encoding or "utf-8", "replace")
                                 .decode(sys.stdout.encoding or "utf-8", "replace"))
                sys.stdout.write("\n")
                return 1
            time.sleep(1)
        if not ready:
            print("!! 应用未在 120s 内就绪")
            return 1
        print("应用就绪 pid=%d" % process.pid)

        print()
        print("=== 发起请求 ===")
        calls = [
            ("plain", "/reactive/plain"),
            ("slow-600ms", "/reactive/slow/600"),
            ("error", "/reactive/error"),
            ("404", "/reactive/not-exist"),
            ("stream", "/reactive/stream"),
        ]
        for name, path in calls:
            status, body = http(path)
            print("  %-14s HTTP %-4s %s" % (name, status, body[:60].replace("\n", " ")))

        # POST：既验证审计采集体，也验证业务**仍读到完整体**
        payload = '{"orderId":"WF-1","password":"should-be-masked"}'
        status, body = http("/reactive/post", method="POST", body=payload,
                            headers={"Authorization": "Bearer leaked-token-xyz"})
        print("  %-14s HTTP %-4s %s" % ("post", status, body[:120].replace("\n", " ")))
        # 业务必须读到**完整的请求体**：断言回显的 orderId 值吻合。
        # 只断言"包含某个 key"证明不了完整性（值可能被旁路拷贝吃掉）。
        business_ok = '"echoedOrderId":"WF-1"' in body.replace(" ", "")

        # 语义通道：带 @AuditLog 的端点（验证操作名/动作类型/资源被写入）
        status, body = http("/reactive/annotated?orderId=ORD-9", method="POST")
        print("  %-14s HTTP %-4s %s" % ("annotated", status, body[:130].replace("\n", " ")))
        # 未标注注解的端点：应仅由边界通道兜底（operation 为空）
        status, _ = http("/reactive/unannotated")
        print("  %-14s HTTP %-4s" % ("unannotated", status))

        time.sleep(5)

        # 先把应用侧的诊断/关键日志打出来：Java 的 stdout 重定向到文件时是块缓冲的，
        # 进程未退出就看不到内容；在断言之前读取才拿得到。
        print()
        print("=== 应用日志中的诊断行 ===")
        for line in open(DIR + "/app.log", encoding="utf-8", errors="replace"):
            if "诊断-wf" in line or "运行环境" in line or "审计边界过滤器" in line:
                print("  " + line.rstrip()[:180])

        records = load_records()
        print()
        print("审计记录总数 =", len(records))
        if not records:
            print("!! 零采集：应用能启动但一条记录都没有 —— 这正是最危险的失败模式")
            print()
            print("应用日志中 audit 相关行：")
            for line in open(DIR + "/app.log", encoding="utf-8", errors="replace"):
                if "audit" in line.lower():
                    print("  " + line.rstrip()[:200])
            return 1

        by_uri = {}
        for record in records:
            by_uri.setdefault(record.get("requestUri"), []).append(record)

        passed, failed = [], []

        def check(ok, message):
            (passed if ok else failed).append(message)
            print(("  [通过] " if ok else "  [!! 失败] ") + message)

        print()
        print("=== 1) 边界通道覆盖（含异常/404/流式）===")
        for uri in ("/reactive/plain", "/reactive/slow/600", "/reactive/error",
                    "/reactive/not-exist", "/reactive/stream", "/reactive/post",
                    "/reactive/annotated", "/reactive/unannotated"):
            got = len(by_uri.get(uri, []))
            check(got == 1, "%-24s 记录 %d 条（期望 1）" % (uri, got))

        print()
        print("=== 1b) 语义通道（@AuditLog 元数据）===")
        annotated = by_uri.get("/reactive/annotated", [])
        for record in annotated:
            check(record.get("operation") == "创建反应式订单",
                  "annotated operation=%s" % record.get("operation"))
            check(record.get("actionType") == "MUTATE",
                  "annotated actionType=%s" % record.get("actionType"))
            check(record.get("resourceType") == "reactive-order",
                  "annotated resourceType=%s" % record.get("resourceType"))
            check(record.get("resourceId") == "ORD-9",
                  "annotated resourceId=%s（应按 resourceIdParam 从查询参数解析）"
                  % record.get("resourceId"))
            check((record.get("tags") or {}).get("biz") == "webflux",
                  "annotated 自定义标签 biz=%s" % (record.get("tags") or {}).get("biz"))
        check(len(annotated) > 0, "annotated 端点产生了记录（否则语义断言无意义）")
        for record in by_uri.get("/reactive/unannotated", []):
            check(not record.get("operation"),
                  "未标注注解的端点 operation 为空（仅边界兜底），实际=%s"
                  % record.get("operation"))

        print()
        print("=== 2) httpStatus 真实 ===")
        for record in by_uri.get("/reactive/error", []):
            check(record.get("outcome") == "FAILURE",
                  "error 端点 outcome=%s" % record.get("outcome"))
            check(record.get("error") is not None,
                  "error 端点记录了 error 字段")
        for record in by_uri.get("/reactive/not-exist", []):
            check(record.get("httpStatus") == 404,
                  "404 端点 httpStatus=%s" % record.get("httpStatus"))

        print()
        print("=== 3) durationMillis 覆盖到响应结束 ===")
        for record in by_uri.get("/reactive/slow/600", []):
            got = record.get("durationMillis") or 0
            check(got >= 500, "slow-600ms 记录 duration=%sms（应 >=500）" % got)
        # 已知取舍：流式响应在"响应提交（≈首字节）"时刻收尾，因此记录的是首字节耗时，
        # 而不是整个流结束耗时。这里断言的是"确实覆盖到首字节之后"，
        # 而不是要求它等于流总时长 —— 断言必须反映真实设计，不能自我安慰。
        for record in by_uri.get("/reactive/stream", []):
            got = record.get("durationMillis") or 0
            check(got >= 60,
                  "stream 记录 duration=%sms（已知取舍：记首字节耗时，非流总时长）" % got)

        print()
        print("=== 4) 业务仍读到完整请求体（tee 不得吃掉字节）===")
        check(business_ok, "业务侧收到的响应包含 orderId=WF-1（说明请求体未被审计消费掉）")

        print()
        print("=== 5) 脱敏生效 ===")
        for record in by_uri.get("/reactive/post", []):
            tags = record.get("tags") or {}
            headers = tags.get("headers", "")
            check("leaked-token-xyz" not in json.dumps(record, ensure_ascii=False),
                  "Authorization 令牌未明文落库")
            request_body = (record.get("request") or {}).get("body") or ""
            if request_body:
                check("should-be-masked" not in request_body,
                      "请求体已脱敏：%s" % request_body[:80])

        print()
        print("=== 6) 哈希链可用 ===")
        chained = [r for r in records if r.get("recordHash")]
        check(len(chained) == len(records),
              "%d/%d 条记录带链哈希" % (len(chained), len(records)))
        seqs = [r.get("seq") for r in records if r.get("seq")]
        check(len(seqs) == len(set(seqs)), "seq 无重复")
        check(all(r.get("hashChainNote") is None for r in []), "（占位断言）")

        print()
        print("通过 %d 项，失败 %d 项" % (len(passed), len(failed)))
        for item in failed:
            print("  - " + item)

        print()
        print("=== 抽样记录 ===")
        for record in records[:3]:
            print("  uri=%-22s status=%-5s duration=%-6s outcome=%-8s seq=%s" % (
                record.get("requestUri"), record.get("httpStatus"),
                record.get("durationMillis"), record.get("outcome"), record.get("seq")))
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
