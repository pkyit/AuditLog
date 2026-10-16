#!/bin/bash
# ============================================================================
# 性能压测执行脚本（在测试服务器上运行）
#
# 设计原则：
#   1. 单变量对照：同一份业务逻辑，只改"审计配置"这一个变量；
#   2. 每个场景独立启动应用，避免上一次运行的状态污染；
#   3. 预热 + 测量分离，剔除 JIT 预热影响；
#   4. 记录 P99/P999 而不只是平均值（SLA 看的是尾部）；
#   5. 同时记录审计链路自身的指标（队列深度/丢弃/WAL），
#      避免"用丢数据换吞吐"却看不出来。
# ============================================================================
set -u

APP_JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
PORT=18083
BASE="http://127.0.0.1:${PORT}"
RESULT_DIR=/root/bench/results
CONNECTIONS=${CONNECTIONS:-200}
THREADS=${THREADS:-4}
DURATION=${DURATION:-60s}
WARMUP_SECONDS=${WARMUP_SECONDS:-20}
HEAP=${HEAP:--Xms1g -Xmx1g}
CPUS=${CPUS:-0-3}          # 应用绑定 4 核
LOAD_CPUS=${LOAD_CPUS:-4-5} # 压测工具绑定 2 核

mkdir -p "$RESULT_DIR"
source /etc/profile.d/audit-java17.sh

log() { echo "[$(date +%H:%M:%S)] $*"; }

wait_ready() {
  for i in $(seq 1 60); do
    if curl -s -m 2 -o /dev/null "${BASE}/actuator/health"; then return 0; fi
    # 应用已退出（启动失败）时立刻返回，避免压测脚本长时间空等
    local pid_file="$1"
    if [ -n "$pid_file" ] && [ -f "$pid_file" ] && ! kill -0 "$(cat "$pid_file")" 2>/dev/null; then
      log "!! 应用进程已退出（很可能是启动校验失败），见 app 日志"
      return 1
    fi
    sleep 1
  done
  log "!! 应用未在 60s 内就绪"
  return 1
}

start_app() {
  local scenario="$1"; shift
  local profiles="$1"; shift
  rm -rf /root/bench/journal /root/bench/audit
  mkdir -p /root/bench/journal /root/bench/audit
  # shellcheck disable=SC2086
  nohup taskset -c "$CPUS" java $HEAP \
      -XX:+UseG1GC -XX:MaxGCPauseMillis=200 \
      -Xlog:gc*:file=/root/bench/results/gc-${scenario}.log:time,uptime:filecount=2,filesize=20M \
      -Dspring.profiles.active="$profiles" \
      -jar "$APP_JAR" > "/root/bench/results/app-${scenario}.log" 2>&1 &
  echo $! > "/root/bench/results/app-${scenario}.pid"
  log "启动场景 ${scenario} (profiles=${profiles}, pid=$(cat /root/bench/results/app-${scenario}.pid))"
  wait_ready "/root/bench/results/app-${scenario}.pid" || return 1
  sleep 3
}

stop_app() {
  local scenario="$1"
  local pid_file="/root/bench/results/app-${scenario}.pid"
  if [ -f "$pid_file" ]; then
    local pid
    pid=$(cat "$pid_file")
    kill "$pid" 2>/dev/null
    for i in $(seq 1 30); do
      kill -0 "$pid" 2>/dev/null || break
      sleep 1
    done
    kill -9 "$pid" 2>/dev/null
    rm -f "$pid_file"
  fi
  sleep 2
}

# 读取审计自身的指标
audit_metrics() {
  curl -s -m 5 "${BASE}/actuator/auditLogMetrics" 2>/dev/null
}

audit_status() {
  curl -s -m 5 "${BASE}/actuator/auditLog" 2>/dev/null \
    | grep -oE '"(queueDepth|droppedRecords|lastDeliveredSeq)":[0-9]+'
}

run_scenario() {
  local scenario="$1"      # 结果文件名
  local profiles="$2"      # spring profiles
  local path="$3"          # 被测 URI
  local label="$4"         # 可读标签
  local post_body="${5:-}" # 非空则用 POST

  log "==================== 场景 ${scenario}: ${label} ===================="
  start_app "$scenario" "$profiles" || { stop_app "$scenario"; return 1; }

  local url="${BASE}${path}"
  if [ -n "$post_body" ]; then
    # POST 场景用 ab（wrk 发 JSON body 需要 lua 脚本，这里用 ab 简化）
    local ab_out="/root/bench/results/ab-${scenario}.txt"
    taskset -c "$LOAD_CPUS" ab -k -n $(( ${CONNECTIONS} * 40 )) -c "$CONNECTIONS" \
        -p /root/bench/payload.json -T application/json "$url" > "$ab_out" 2>&1
    log "ab 完成 -> $ab_out"
    grep -E 'Requests per second|Time per request|Failed requests|Complete requests|Transfer rate|50%|95%|99%|100%' "$ab_out" | sed 's/^/    /'
  else
    # 预热
    log "预热 ${WARMUP_SECONDS}s ..."
    taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d"${WARMUP_SECONDS}s" "$url" > /dev/null 2>&1
    # 测量
    local wrk_out="/root/bench/results/wrk-${scenario}.txt"
    log "测量: wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency"
    taskset -c "$LOAD_CPUS" wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency "$url" > "$wrk_out" 2>&1
    cat "$wrk_out" | sed 's/^/    /'
  fi

  log "审计链路状态: $(audit_status)"
  log "审计指标快照: $(audit_metrics)"
  audit_metrics > "/root/bench/results/metrics-${scenario}.json" 2>/dev/null
  audit_status > "/root/bench/results/status-${scenario}.txt" 2>/dev/null

  stop_app "$scenario"
  log "场景 ${scenario} 结束"
  echo
}

# ---------------------------------------------------------------------------
mkdir -p /root/bench
# 200 字节左右的 JSON 载荷（贴近真实业务报文）
python3 - <<'PY'
import json
body = {"orderId": "ORD-2024-000001", "userId": "u-10086", "amount": 199.99,
        "remark": "load test payload " + "x" * 120,
        "password": "ShouldNotAppearInAudit-123",
        "idCard": "110101199003071234", "mobile": "13812345678"}
open("/root/bench/payload.json", "w").write(json.dumps(body))
PY
log "载荷大小: $(wc -c < /root/bench/payload.json) 字节"

# 场景 A：完全无审计（框架两通道都关闭）—— 真正的基线
run_scenario "A-no-audit"  "bench,bench-off"  "/load/plain?payload=hello" "A 基线：审计完全关闭"
# 场景 B：框架开启，端点不产生记录（但边界通道会记录）
run_scenario "B-boundary"  "bench"            "/load/plain?payload=hello" "B 仅边界采集：未标注注解的端点"
# 场景 C：原文章测法（注解存在但三开关全关）
run_scenario "C-switch-off" "bench"           "/load/off?payload=hello"   "C 复现原文章：注解在但采集全关"
# 场景 D：采集全开 + 内存存储
run_scenario "D-full-logging" "bench"         "/load/full?payload=$(python3 -c 'print("p"*180)')" "D 采集全开（请求头+请求体+脱敏，内存存储）"
# 场景 E：采集全开 + 真实落盘 + 哈希链
run_scenario "E-full-file"  "bench,bench-file" "/load/full?payload=$(python3 -c 'print("p"*180)')" "E 采集全开 + JSON 落盘 + 哈希链（生产最接近）"

log "全部场景完成，结果在 ${RESULT_DIR}"
