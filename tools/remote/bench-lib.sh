#!/bin/bash
# ============================================================================
# 压测函数库（被 bench.sh 与 run-scenarios.sh 共同 source）
#
# 设计原则：
#   1. 单变量对照：同一份业务逻辑，只改"审计配置"这一个变量；
#   2. 每个场景独立启动应用，避免上次运行的状态污染；
#   3. 预热 + 测量分离，剔除 JIT 预热影响；
#   4. 记录 P99/P999 而不只是平均值（SLA 看的是尾部）；
#   5. 同时记录审计链路自身指标（队列深度/丢弃/WAL），
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
CPUS=${CPUS:-0-3}            # 应用绑定 4 核
LOAD_CPUS=${LOAD_CPUS:-4-5}  # 压测工具绑定 2 核

mkdir -p "$RESULT_DIR"
source /etc/profile.d/audit-java17.sh

log() { echo "[$(date +%H:%M:%S)] $*"; }

wait_ready() {
  local pid_file="${1:-}"
  for i in $(seq 1 60); do
    if curl -s -m 2 -o /dev/null "${BASE}/actuator/health"; then return 0; fi
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
  local scenario="$1"
  local profiles="$2"
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

audit_metrics() { curl -s -m 5 "${BASE}/actuator/auditLogMetrics" 2>/dev/null; }

audit_status() {
  curl -s -m 5 "${BASE}/actuator/auditLog" 2>/dev/null \
    | grep -oE '"(queueDepth|droppedRecords|lastDeliveredSeq)":[0-9]+'
}

run_scenario() {
  local scenario="$1"
  local profiles="$2"
  local path="$3"
  local label="$4"

  log "==================== 场景 ${scenario}: ${label} ===================="
  start_app "$scenario" "$profiles" || { stop_app "$scenario"; return 1; }

  local url="${BASE}${path}"
  log "预热 ${WARMUP_SECONDS}s ..."
  taskset -c "$LOAD_CPUS" wrk -t2 -c50 -d"${WARMUP_SECONDS}s" "$url" > /dev/null 2>&1

  local wrk_out="/root/bench/results/wrk-${scenario}.txt"
  log "测量: wrk -t${THREADS} -c${CONNECTIONS} -d${DURATION} --latency"
  taskset -c "$LOAD_CPUS" wrk -t"$THREADS" -c"$CONNECTIONS" -d"$DURATION" --latency "$url" > "$wrk_out" 2>&1
  cat "$wrk_out" | sed 's/^/    /'

  log "审计链路状态: $(audit_status)"
  audit_metrics > "/root/bench/results/metrics-${scenario}.json" 2>/dev/null
  audit_status > "/root/bench/results/status-${scenario}.txt" 2>/dev/null
  log "审计指标: $(cat /root/bench/results/metrics-${scenario}.json)"

  stop_app "$scenario"
  log "场景 ${scenario} 结束"
  echo
}
