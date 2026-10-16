#!/bin/bash
# 运行指定场景（可复用 bench-lib.sh 里的函数）
# 用法: bash run-scenarios.sh B-boundary C-switch-off D-full-logging E-full-file
set -u
source /root/bench-lib.sh

PAYLOAD=$(python3 -c 'print("p"*180)')

for scenario in "$@"; do
  case "$scenario" in
    A-no-audit)     run_scenario "A-no-audit"     "bench,bench-off"  "/load/plain?payload=hello" "A 基线：审计完全关闭" ;;
    B-boundary)     run_scenario "B-boundary"     "bench"            "/load/plain?payload=hello" "B 仅边界采集：未标注注解的端点" ;;
    C-switch-off)   run_scenario "C-switch-off"   "bench"            "/load/off?payload=hello"   "C 复现原文章：注解在但采集全关" ;;
    D-full-logging) run_scenario "D-full-logging" "bench"            "/load/full?payload=${PAYLOAD}" "D 采集全开（内存存储）" ;;
    E-full-file)    run_scenario "E-full-file"    "bench,bench-file" "/load/full?payload=${PAYLOAD}" "E 采集全开 + JSON 落盘 + 哈希链" ;;
    F-overload)     run_scenario "F-overload"     "bench,bench-overload" "/load/full?payload=${PAYLOAD}" "F 过载：小队列强制触发背压（验证 SPILL 不丢数据）" ;;
    *) echo "未知场景: $scenario" ;;
  esac
done
echo "[run-scenarios] 全部指定场景完成"
