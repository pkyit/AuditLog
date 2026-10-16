#!/bin/bash
# 在 103 上启动/停止供 102 远程压测的应用实例（监听所有网卡）
set -u
source /etc/profile.d/audit-java17.sh

ACTION="${1:-start}"
PROFILE="${2:-bench}"
PORT=19500
JAR=/root/audit-log/audit-log-samples/target/audit-log-samples-1.0.0.jar
DIR=/root/cross-bench
PIDFILE="$DIR/app.pid"

mkdir -p "$DIR/audit" "$DIR/journal"

stop_app() {
  if [ -f "$PIDFILE" ]; then
    local pid
    pid=$(cat "$PIDFILE")
    kill "$pid" 2>/dev/null
    for i in $(seq 1 30); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -9 "$pid" 2>/dev/null
    rm -f "$PIDFILE"
  fi
  sleep 2
  echo "应用已停止"
}

case "$ACTION" in
  stop)
    stop_app
    ;;
  start)
    stop_app
    rm -rf "$DIR/audit" "$DIR/journal"
    mkdir -p "$DIR/audit" "$DIR/journal"
    # 监听 0.0.0.0 以便 102 访问；应用绑 4 核，留 2 核给系统
    nohup taskset -c 0-3 java -Xms1g -Xmx1g -XX:+UseG1GC \
      -Dspring.profiles.active="$PROFILE" \
      -Dserver.port="$PORT" \
      -Dserver.address=0.0.0.0 \
      -Daudit.log.store.type=json-file \
      -Daudit.log.store.directory="$DIR/audit" \
      -Daudit.log.journal.directory="$DIR/journal" \
      -jar "$JAR" > "$DIR/app.log" 2>&1 &
    echo $! > "$PIDFILE"
    for i in $(seq 1 90); do
      curl -s -m 2 -o /dev/null "http://127.0.0.1:${PORT}/actuator/health" && break
      kill -0 "$(cat "$PIDFILE")" 2>/dev/null || { echo "!! 启动失败"; tail -20 "$DIR/app.log"; exit 1; }
      sleep 1
    done
    echo "应用已启动 pid=$(cat "$PIDFILE") profile=$PROFILE port=$PORT"
    # 确认从外部可达
    ss -lnt | grep ":${PORT}" | sed 's/^/  /'
    ;;
  *)
    echo "用法: $0 {start|stop} [profile]"; exit 1 ;;
esac
