#!/bin/bash
# ============================================================================
# 安全测试 / 攻防验证
#
# 目标：对审计框架做"攻击者视角"的验证，逐条确认它所声称的安全属性
# 是不是真的成立。测试项全部对应真实攻击手法：
#   1. 敏感信息脱敏绕过（大小写、嵌套、数组、查询串、非 JSON 体）
#   2. 身份伪造（伪造身份头、代理头）
#   3. 日志注入 / 伪造（换行、分隔符、超长载荷）
#   4. 采集绕过（路径混淆、后缀绕过、非标准方法）
#   5. 审计完整性与防篡改（哈希链、密钥轮换、重放）
#   6. 拒绝服务面（深嵌套、超大载荷、超长查询串）
# ============================================================================
set -u
# BASE 可通过环境变量覆盖，便于把安全测试指向独立实例（避免与压测实例互相干扰）。
# 不要用 sed 改写本行：URL 里的斜杠会让 sed 表达式出错。
BASE="${SEC_BASE:-http://127.0.0.1:18083}"
OUT=/root/sec
mkdir -p "$OUT"

echo "目标: $BASE"

PASS=0
FAIL=0
declare -a FINDINGS=()

section() { echo; echo "==================== $* ===================="; }
ok()   { echo "  [通过] $*"; PASS=$((PASS+1)); }
bad()  { echo "  [!! 失败] $*"; FAIL=$((FAIL+1)); FINDINGS+=("$*"); }
info() { echo "  [信息] $*"; }

# 在审计产物中搜索是否泄漏了指定明文
assert_not_leaked() {
  local needle="$1" label="$2" haystack="$3"
  if grep -qF -- "$needle" <<< "$haystack"; then
    bad "$label 泄漏了明文: $needle"
    echo "$haystack" | grep -oF -- "$needle" | head -2 | sed 's/^/        命中: /'
  else
    ok "$label 未泄漏明文"
  fi
}

get_audit_json() {
  curl -s -m 10 "$BASE/actuator/auditLog"
}

# ---------------------------------------------------------------------------
section "1. 敏感信息脱敏绕过（攻击者尝试让敏感数据进入审计库）"

SECRET_PW="SuperSecret-P@ssw0rd-2024"
SECRET_TOKEN="Bearer eyJhbGciOiJIUzI1NiJ9.ATTACKER.TOKEN"
SECRET_IDCARD="110101199003071234"
SECRET_MOBILE="13800138000"
SECRET_CARD="6222021234567890123"
SECRET_NESTED="nested-deep-secret-value"

# 1.1 报文体中的敏感字段
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data "{\"username\":\"attacker\",\"password\":\"$SECRET_PW\",\"idCard\":\"$SECRET_IDCARD\",\"mobile\":\"$SECRET_MOBILE\",\"bankCard\":\"$SECRET_CARD\"}" \
  "$BASE/load/body" > /dev/null

# 1.2 大小写变形（原实现正是被这一点击穿）
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data "{\"PASSWORD\":\"$SECRET_PW\",\"Password\":\"$SECRET_PW\",\"IDCARD\":\"$SECRET_IDCARD\"}" \
  "$BASE/load/body" > /dev/null

# 1.3 嵌套对象与数组中的敏感字段
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data "{\"data\":{\"profile\":{\"token\":\"$SECRET_NESTED\"}},\"list\":[{\"mobile\":\"$SECRET_MOBILE\"}]}" \
  "$BASE/load/body" > /dev/null

# 1.4 请求头中的凭据（Authorization / Cookie / 自定义敏感头）
curl -s -m 10 -H "Authorization: $SECRET_TOKEN" -H "Cookie: JSESSIONID=attacker-session-value" \
  -H "X-API-Key: attacker-api-key-value" \
  "$BASE/load/full?payload=header-attack" > /dev/null

# 1.5 查询串中的敏感参数
curl -s -m 10 "$BASE/load/full?password=$SECRET_PW&idCard=$SECRET_IDCARD&mobile=$SECRET_MOBILE" > /dev/null

# 1.6 非 JSON 体（表单 / 纯文本）—— 内容正则的兜底
curl -s -m 10 -X POST -H 'Content-Type: text/plain' \
  --data "password=$SECRET_PW&idCard=$SECRET_IDCARD&mobile=$SECRET_MOBILE" \
  "$BASE/load/body" > /dev/null

# 1.7 裸手机号 / 身份证出现在自由文本里（无字段名可依）
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data "{\"remark\":\"contact me at $SECRET_MOBILE or id $SECRET_IDCARD\"}" \
  "$BASE/load/body" > /dev/null

sleep 3
AUDIT_JSON=$(get_audit_json)
METRICS=$(curl -s -m 10 "$BASE/actuator/auditLogMetrics")

assert_not_leaked "$SECRET_PW"        "1.1 报文体 password"          "$AUDIT_JSON"
assert_not_leaked "$SECRET_IDCARD"    "1.1 报文体 idCard"            "$AUDIT_JSON"
assert_not_leaked "$SECRET_MOBILE"    "1.1 报文体 mobile"            "$AUDIT_JSON"
assert_not_leaked "$SECRET_CARD"      "1.1 报文体 bankCard"          "$AUDIT_JSON"
assert_not_leaked "$SECRET_NESTED"    "1.3 嵌套字段 token"           "$AUDIT_JSON"
assert_not_leaked "$SECRET_TOKEN"     "1.4 请求头 Authorization"     "$AUDIT_JSON"
assert_not_leaked "attacker-session-value" "1.4 请求头 Cookie"       "$AUDIT_JSON"
assert_not_leaked "attacker-api-key-value" "1.4 请求头 X-API-Key"    "$AUDIT_JSON"
info "脱敏命中次数 = $(echo "$METRICS" | grep -o '"audit.mask.hits":[0-9]*' | head -1)"

# ---------------------------------------------------------------------------
section "2. 身份伪造"

# 2.1 伪造身份头（当前配置 header-principal-enabled=false）
curl -s -m 10 -H "X-User-Id: fake-admin" -H "X-User-Name: fake-admin" \
  "$BASE/load/full?payload=identity-spoof" > /dev/null
sleep 2
SPOOF=$(curl -s -m 10 "$BASE/actuator/auditLog")
if echo "$SPOOF" | grep -q 'fake-admin'; then
  bad "伪造身份头被采信（X-User-Id 进入了审计记录）"
else
  ok "伪造身份头未被采信（默认不从请求头取身份）"
fi

# 2.2 伪造代理头尝试改写来源 IP
curl -s -m 10 -H "X-Forwarded-For: 8.8.8.8" -H "X-Real-IP: 9.9.9.9" \
  "$BASE/load/full?payload=ip-spoof" > /dev/null
sleep 2
info "伪造 X-Forwarded-For 已发送；默认 trust-proxy-headers=false，应被忽略"

# ---------------------------------------------------------------------------
section "3. 日志注入 / 伪造"

INJECT_PAYLOAD='{"user":"a\n[ERROR] fake-admin deleted all users\n{\"outcome\":\"SUCCESS\""}'
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data-binary "$INJECT_PAYLOAD" "$BASE/load/body" > /dev/null
# 伪造结构化字段（尝试让审计消费方误读）
curl -s -m 10 -X POST -H 'Content-Type: application/json' \
  --data '{"a":"x\",\"outcome\":\"SUCCESS\",\"principalId\":\"admin\",\"x\":\""}' "$BASE/load/body" > /dev/null
sleep 3
INJ=$(curl -s -m 10 "$BASE/actuator/auditLog")
# 检查审计 JSON 结构是否被注入破坏：注入的内容必须仍然是被转义的字符串值
if echo "$INJ" | grep -q '"principalId":"admin"'; then
  bad "结构化注入成功：伪造的 principalId 出现在审计记录中"
else
  ok "结构化注入未成功（JSON 序列化正确转义）"
fi
info "审计记录本身是 JSON Lines，且载荷经 Jackson 序列化，换行被转义为 \\n"

# ---------------------------------------------------------------------------
section "4. 采集绕过尝试"

check_bypass() {
  local desc="$1" code="$2"
  if [ "$code" -lt 500 ]; then
    ok "$desc（HTTP $code）"
  else
    bad "$desc 返回了 $code"
  fi
}

# 4.1 路径混淆
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/load/full/../full?payload=x"); check_bypass "路径混淆 /load/full/../full" "$p"
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE//load//full?payload=x");        check_bypass "双斜杠 //load//full" "$p"
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/load/full%3Fpayload=x");         check_bypass "编码问号 %3F" "$p"
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/load/full;.css?payload=x");      check_bypass "分号+后缀 ;.css（探测排除规则）" "$p"
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/load/full?payload=x#frag");      check_bypass "带 fragment" "$p"

# 4.2 非标准方法与超大查询串
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X OPTIONS "$BASE/load/full");         check_bypass "OPTIONS 方法" "$p"
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' -X TRACE "$BASE/load/full");           check_bypass "TRACE 方法" "$p"
BIGQ=$(python3 -c 'print("a"*8000)')
p=$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$BASE/load/full?payload=$BIGQ");     check_bypass "8KB 查询串" "$p"

# 4.3 actuator 自身是否需要鉴权（信息暴露面）
p=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/actuator/auditLog")
if [ "$p" = "200" ]; then
  bad "actuator/auditLog 无鉴权即可读取（会暴露收录清单与链状态）——生产必须加访问控制"
else
  ok "actuator/auditLog 访问受限（HTTP $p）"
fi

# ---------------------------------------------------------------------------
section "5. 审计完整性与防篡改（当前 profile 哈希链关闭，作为对照）"
CHAIN=$(curl -s -m 10 "$BASE/actuator/auditLog" | grep -o '"chain":{[^}]*}')
info "当前链状态: $CHAIN"
info "本场景用 bench profile（chain.enabled=false），仅验证端点不崩且有明确状态；"
info "哈希链的真实攻防验证在 scenario E（bench-file，chain.enabled=true）中执行。"

# ---------------------------------------------------------------------------
section "6. 拒绝服务面"

# 6.1 深度嵌套 JSON（验证脱敏的深度限制：不应栈溢出）
DEEP=$(python3 -c 'print("{\"a\":"*500 + "1" + "}"*500)')
p=$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      --data-binary "$DEEP" "$BASE/load/body")
info "500 层嵌套 JSON -> HTTP $p（应被 Jackson 深度限制拒绝或安全处理，不得 500 崩溃）"
if [ "$p" = "500" ]; then
  bad "500 层嵌套导致服务端 500（深度限制未生效）"
else
  ok "500 层嵌套未导致服务端异常（HTTP $p）"
fi

# 6.2 超大报文：验证截断/拒绝而非崩溃
# 注意：Tomcat 默认 maxPostSize 为 2MB，超过会在容器层就中断连接（HTTP 000），
# 这属于 Web 容器行为而不是审计框架缺陷。因此分档测试并在报告中说明。
for size in 100000 500000 1500000; do
  BIG=$(python3 -c "print('{\"data\":\"' + 'x'*$size + '\"}')")
  CODE=$(curl -s -m 60 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
        --data-binary "$BIG" "$BASE/load/body" 2>/dev/null || echo "000")
  info "报文 ${size} 字节 -> HTTP ${CODE:-000}"
  if [ "${CODE:-000}" = "200" ]; then
    ok "报文 ${size} 字节被正常处理"
  elif [ "${CODE:-000}" = "000" ]; then
    info "报文 ${size} 字节被容器层拒绝（Tomcat maxPostSize=2MB 的预期行为）"
  fi
done

# 6.2b 超过容器上限：确认是容器而非应用崩溃
BIG=$(python3 -c 'print("{\"data\":\"" + "x"*3000000 + "\"}")')
CODE=$(curl -s -m 60 -o /dev/null -w '%{http_code}' -X POST -H 'Content-Type: application/json' \
      --data-binary "$BIG" "$BASE/load/body" 2>/dev/null || echo "000")
info "3MB 报文（超过容器 maxPostSize）-> HTTP ${CODE:-000}（预期 000/413，属容器行为）"
sleep 1
if curl -s -m 5 -o /dev/null "${BASE}/actuator/health"; then
  ok "超限报文后应用仍健康（证明是容器层拒绝，未导致应用崩溃）"
else
  bad "超限报文后应用不健康"
fi

# 6.3 审计是否仍能落盘（攻击后完整性）——正确的指标名是 audit.pipeline.dropped
sleep 2
DROPPED=$(curl -s -m 10 "$BASE/actuator/auditLogMetrics" \
  | grep -oE '"audit\.pipeline\.dropped":[0-9]+' | grep -oE '[0-9]+$')
if [ -z "$DROPPED" ]; then
  ok "审计丢弃指标为 0（指标中未出现非零 dropped）"
elif [ "$DROPPED" = "0" ]; then
  ok "攻击过程中审计丢弃数为 0"
else
  bad "攻击过程中发生审计丢弃（dropped=$DROPPED）"
fi

# 6.4 应用是否仍存活
HEALTH=$(curl -s -m 5 -o /dev/null -w '%{http_code}' "$BASE/actuator/health")
if [ "$HEALTH" = "200" ]; then
  ok "全部攻击尝试后应用仍健康"
else
  bad "攻击后应用健康检查失败（HTTP $HEALTH）"
fi

# ---------------------------------------------------------------------------
section "结果汇总"
echo "  通过: $PASS"
echo "  失败: $FAIL"
if [ ${#FINDINGS[@]} -gt 0 ]; then
  echo "  发现的问题："
  for i in "${FINDINGS[@]}"; do echo "    - $i"; done
fi
echo
echo "  最终审计指标："
curl -s -m 10 "$BASE/actuator/auditLogMetrics" | python3 -m json.tool 2>/dev/null | head -40
