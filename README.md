# audit-log —— 企业级可插拔日志审计框架

面向 Spring Boot 的**零侵入、可插拔**日志审计组件。引入一个依赖即可获得：
全链路采集、事务结果对齐、敏感信息脱敏、防篡改哈希链、不丢数据的背压与本地兜底、以及开箱可用的健康检查与诊断端点。

> **与上一版的区别**：本版本由架构评审驱动重写（评审报告见 [`docs/01-架构评审报告.md`](docs/01-架构评审报告.md)，
> 整改方案见 [`docs/02-整改方案.md`](docs/02-整改方案.md)，方案自评审见 [`docs/02b-整改方案自评审.md`](docs/02b-整改方案自评审.md)）。
> 原技术原型完整保留在 [`legacy/audit-log-prototype`](legacy/audit-log-prototype) 以便对照，不再参与构建。

---

## 1. 它在解决什么问题

审计日志与普通业务日志的区别在于：**它必须能被信任**。因此本框架以四条不变量为设计约束，
所有实现细节都服从它们：

| # | 不变量 | 具体保证 | 对应实现 |
|---|---|---|---|
| **I1** | 身份不可伪造 | 主体只来自认证上下文；请求头来源必须显式开启且被标注为 `TRUSTED_HEADER` | `PrincipalResolver` 链、`PrincipalType` |
| **I2** | 结果真实 | 状态码取自真实响应；事务回滚**不产生**"成功"记录；异常记录类型 + 根因 | `TransactionAuditTracker`、`Outcome`、`ErrorInfo` |
| **I3** | 事件不丢 | 有界队列 + 显式背压；存储故障降级本地 WAL；重试/死信；停机排空；每一步都有指标 | `AuditPipeline`、`BoundedRingQueue`、`FileAuditJournal` |
| **I4** | 历史不可篡改 | 单调序号 + `prevHash`/`recordHash` 链，可选 HMAC 签名；可随时校验并定位被改动的那条 | `Sha256AuditChain`、`HmacAuditChain` |

---

## 2. 快速开始（任意 Spring Boot 项目）

### 2.1 加依赖

```xml
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>audit-log-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

**无需任何注解**。自动配置会完成装配（`META-INF/spring/...AutoConfiguration.imports`）。

该坐标已发布到 Maven Central，无需本地构建即可直接拉取：

<https://central.sonatype.com/artifact/io.github.pkyit/audit-log-spring-boot-starter>

构件均附带 `-sources.jar`、`-javadoc.jar` 与 GPG 签名。仅需引入 starter 一个依赖，
`audit-log-core` 会自动带入；两种 Web 环境（Servlet / Reactive）由自动配置按运行环境选择。

### 2.2 标注需要审计的方法

```java
@RestController
@RequestMapping("/api/user")
public class UserController {

    @AuditLog(name = "创建用户", actionType = ActionType.MUTATE,
              resourceType = "user", sensitive = true)
    @PostMapping("/create")
    public Result create(@RequestBody CreateUserRequest request) { ... }

    // 未标注的方法也会被"边界通道"采集（来源、状态码、耗时），
    // 只是缺少操作名等语义字段 —— 保证认证失败/403/404 这类关键事件不漏采
    @GetMapping("/{id}")
    public User get(@PathVariable Long id) { ... }
}
```

### 2.3 配置存储（生产必须）

```yaml
audit:
  log:
    service-name: order-service
    store:
      type: json-file          # logging(默认,不适合生产) | json-file | jdbc
      directory: /var/log/audit
      max-history: 90
    chain:
      enabled: true
      secret: ${AUDIT_CHAIN_SECRET}   # 从环境变量/密钥管理注入
```

启动时会打印明确提示，告诉你当前配置是否符合合规留存要求。

---

## 3. 工作原理：双通道采集

```
                ┌────────────────────────────────────────────────┐
  HTTP 请求 ──▶ │ 边界通道：AuditLogServletFilter (order = -100)  │
                │  · 建立唯一审计上下文（主体/来源/链路/租户）      │
                │  · 认证失败、401/403、限流、404、绑定失败都覆盖   │
                └───────────────┬────────────────────────────────┘
                                │ 填充同一个 AuditRecordContext
                ┌───────────────▼────────────────────────────────┐
                │ 语义通道：AuditLogHandlerMethod（HandlerAdapter）│
                │  · 补操作名 / 动作类型 / 资源 ID / 耗时          │
                │  · 注册事务同步回调，捕获提交或回滚              │
                │  · 可整体关闭，仅退化为边界采集（不会丢事件）    │
                └───────────────┬────────────────────────────────┘
                                │ 请求结束：填真实状态码与结果
                                │ **唯一一次**发布（无合并竞态）
                ┌───────────────▼────────────────────────────────┐
                │ 脱敏（请求线程内完成，入队即已脱敏）             │
                └───────────────┬────────────────────────────────┘
                ┌───────────────▼────────────────────────────────┐
                │ 有界队列 + 背压（默认 SPILL）                    │
                └───────┬───────────────────────┬────────────────┘
                        │                       │ 队列满
                ┌───────▼────────┐      ┌───────▼─────────────────┐
                │ 消费者（默认1） │      │ 本地兜底日志 WAL        │
                │ 链式哈希 → 批量 │      │ （独立写线程，不阻塞）  │
                └───────┬────────┘      └───────┬─────────────────┘
                        │ 失败                  │ 恢复后重放
                        ▼                       ▼
                  重试 → 死信 ──────────▶ AuditStore（可插拔）
```

**为什么是"两阶段填充一条记录"而不是"两条记录事后合并"**：后者在异步投递下存在竞态
（语义记录与边界记录到达顺序不确定，粗粒度记录可能覆盖语义记录），
会把审计最需要的操作名与资源信息丢掉。详见 [ADR 文档](docs/03-架构决策记录(ADR).md) 的 ADR-002。

---

## 4. 配置项总览

| 配置 | 默认 | 说明 |
|---|---|---|
| `audit.log.enabled` | `true` | 总开关 |
| `audit.log.collector.boundary.enabled` | `true` | 边界通道（建议保持开启） |
| `audit.log.collector.boundary.include-patterns` | `/**` | ⚠️ 不要写成 `/*`（Ant 语法只匹配单层路径） |
| `audit.log.collector.method.enabled` | `true` | 语义通道（与宿主 `WebMvcRegistrations` 互斥时可关闭） |
| `audit.log.collector.method.serialize-arguments` | `false` | 是否序列化方法实参（默认关闭，改用请求体快照） |
| `audit.log.masking.sensitive-fields` | 见下 | 字段路径规则，支持 `$`、`*`、`**`、`[*]` |
| `audit.log.masking.sensitive-headers` | `[]` | 额外敏感请求头（`Authorization`/`Cookie` 等**恒生效且不可覆盖**） |
| `audit.log.store.type` | `logging` | `logging` / `json-file` / `jdbc` / 自定义 Bean |
| `audit.log.pipeline.strategy` | `SPILL` | `SPILL` / `BLOCK` / `DROP_OLDEST` / `DROP_NEWEST` / `FAIL_FAST` |
| `audit.log.pipeline.queue-capacity` | `8192` | 有界队列容量 |
| `audit.log.pipeline.max-retries` | `3` | 投递失败重试次数 |
| `audit.log.journal.enabled` | `true` | 本地兜底日志（SPILL 策略与不丢数据的基础） |
| `audit.log.chain.enabled` | `false` | 哈希链（有成本，需显式开启） |
| `audit.log.chain.secret` | `""` | HMAC 密钥；为空则退化为无密钥 SHA-256 链（**不抗有写权限的攻击者**） |
| `audit.log.drop-on-rollback` | `true` | 事务回滚不产生记录（避免"记录了没发生的事"） |
| `audit.log.identity.header-principal-enabled` | `false` | 是否允许从请求头取主体 |
| `audit.log.identity.trust-proxy-headers` | `false` | 是否信任 `X-Forwarded-For`（需配 `trusted-proxies`） |
| `audit.log.validation.fail-on-mismatch` | `true` | 注解标注了却不采集 → 启动失败 |
| `audit.log.actuator.*` | `true` | 健康检查 / 指标 / 管理端点 |

默认字段脱敏规则：

```yaml
$.password, $.oldPassword, $.newPassword, $.confirmPassword,
$.token, $.accessToken, $.refreshToken, $.secret, $.apiKey,
$.idCard, $.idNumber, $.bankCard, $.cardNo, $.cvv, $.mobile, $.phone
```

外加内置内容正则：Bearer/JWT 令牌、身份证、银行卡、手机号、邮箱。

---

## 5. 背压策略怎么选（这是必须做的决策）

| 策略 | 业务影响 | 数据影响 | 适用 |
|---|---|---|---|
| **SPILL**（默认） | 无阻塞 | **不丢**（落本地 WAL，可重放） | 绝大多数合规场景 |
| BLOCK | **阻塞业务线程，直接影响 SLA** | 不丢 | 审计必须与业务同速 |
| DROP_OLDEST / DROP_NEWEST | 无阻塞 | **真实丢失**，必须配告警 | 仅"尽力审计" |
| FAIL_FAST | **审计不可用则业务不可用** | 不丢 | 强合规（无审计不得操作） |

框架不替使用者假装这个冲突不存在 —— 启动时会明确打印你所选策略的代价。

---

## 6. 扩展点（SPI）

提供同类型 Bean 即可接管，全部支持 `@ConditionalOnMissingBean`：

| SPI | 职责 | 内置实现 |
|---|---|---|
| `PrincipalResolver` | 主体解析（按 order 成链） | Security 上下文 / 请求头 / 匿名 |
| `ClientIpResolver` | 真实 IP（默认不信任代理头） | `DefaultClientIpResolver` |
| `TenantResolver` / `TraceIdResolver` | 租户 / 链路 ID | 请求头解析 |
| `AuditMasker` | 脱敏 | `RuleBasedAuditMasker` |
| `AuditSerializer` | 快照序列化 | `JacksonAuditSerializer` |
| `AuditStore` | 存储 | logging / json-file / jdbc |
| `AuditJournal` | 本地兜底与重放 | `FileAuditJournal` |
| `AuditChain` | 防篡改链 | SHA-256 / HMAC-SHA256 / 关闭 |
| `AuditFailureHandler` | 失败告警钩子 | 日志（生产建议接告警平台） |
| `AuditMetrics` | 指标 | `SimpleAuditMetrics`（可换 Micrometer 实现） |
| `AuditCodec` | 线格式编解码 | JSON Lines |

示例（自定义主体来源，见 `SamplePrincipalConfiguration`）：

```java
@Bean
public PrincipalResolver myPrincipalResolver() {
    return context -> Optional.ofNullable(context.getHeader("X-My-Auth"))
        .map(id -> new PrincipalInfo(id, id, PrincipalType.AUTHENTICATED, "my-auth", null));
}
```

---

## 7. 可观测性

**指标**（`GET /actuator/auditLogMetrics`）。必须对以下两项配置告警：

| 指标 | 含义 |
|---|---|
| `audit.pipeline.dropped` | **审计数据已经丢失**（最严重） |
| `audit.pipeline.journal.rejected` | 本地兜底也写不进去，需人工介入 |
| `audit.store.deadletter` | 重试耗尽进入死信 |
| `audit.pipeline.backpressure` | 队列打满次数（容量不足的早期信号） |
| `audit.collect.total` / `audit.store.delivered` | 采集与投递总量 |
| `audit.pipeline.queue.depth` / `audit.pipeline.journal.pending` | 积压水位 |

**健康检查**（`GET /actuator/health`）：队列积压超阈值、存在丢弃、存储不健康 → `DOWN`。

**诊断端点**（`GET /actuator/auditLog`）：输出收录清单（哪些方法真的在采集）、
流水线状态与**链校验结果** —— 用于人工核对"注解是否生效"，而不是靠猜。

---

## 8. 验证

### 8.1 构建

```bash
mvn -o install -Dmaven.test.skip=true     # 离线环境
mvn install                                # 联网环境（标准 mvn test 亦可）
```

### 8.2 单元测试（62 项）

```bash
mvn -o test-compile -Dmaven.test.skip=false -DskipTests=true   # 编译测试源码
powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-tests.ps1
```

实测结果：

```
==================== audit-log-core ====================
 发现用例: 55，通过: 55，失败/异常: 0
==================== audit-log-spring-boot-starter ====================
 发现用例: 7，通过: 7，失败/异常: 0
全部单元测试通过。
```

覆盖范围（都是**曾经出过真实缺陷**的地方，因此必须锁死为回归用例）：

| 测试类 | 锁住的行为 |
|---|---|
| `FieldPathMatcherTest` | 字段路径 glob 语义：嵌套、数组、大小写、`*`/`**`/`[*]` —— 曾经 `$.token` 匹配不到 `$.nested.token` 导致**敏感字段静默泄漏** |
| `RuleBasedAuditMaskerTest` | 请求头大小写不敏感、核心黑名单不可覆盖、报文体递归脱敏、内容正则、非法 JSON 降级、截断标记 |
| `BoundedRingQueueTest` | 有界性、满时明确拒绝、`DROP_OLDEST` 返回被弃者、4×4 并发下不丢不重、超时返回 |
| `AuditChainTest` | 完好链通过、篡改/删除被发现并**定位 seq**、HMAC 错误密钥无法伪造、链字段不参与摘要 |
| `AuditPipelineTest` | 存储故障→死信逐条落盘、`dropped=0`、WAL 重放、停机排空、异常隔离、序号单调、策略契约、未启动时不静默 |
| `AuditCaptureServiceTest` | 主体解析器故障降级为匿名且计数、链路 ID 回退、状态码→结果判定、语义字段填充、采集期脱敏、停机干净 |

> **关于 `mvn test`**：本仓库开发环境离线，且本地 Maven 仓库中 `maven-surefire-plugin`
> 的 provider 依赖只有空目录、没有 jar。因此测试通过 `tools/run-tests.ps1` 直接调用
> **JUnit Platform Launcher** 执行（与 surefire 跑的是同一套 JUnit 5 引擎）。
> 在联网环境中，只需在父 POM 的 `<build><plugins>` 中加入 `maven-surefire-plugin`，
> 即可用标准 `mvn test` 获得同样结果 —— 测试源码无需任何改动。

### 8.3 端到端验收（11 项，推荐）

`demo` profile 会在应用启动后，用真实 HTTP 请求跑完 11 项验收并以退出码反映结果
（可直接用于 CI）：

```bash
# 1) 生成运行时 classpath（离线环境脚本）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/build-classpath.ps1

# 2) 启动并验收
java -Dspring.profiles.active=demo -Daudit.demo.exit=true \
     -cp "$(Get-Content -Raw audit-log-samples/target/cp.txt)" \
     io.github.pkyit.auditlog.samples.AuditLogSampleApplication
```

实测结果（本仓库当前代码）：

```
 结果：通过 11 项，失败 0 项
[audit-acceptance] 退出码 = 0
```

| 验收项 | 内容 |
|---|---|
| A1 | 引入依赖即生效（示例应用**没有** `@EnableAuditLog`、没有 `@EnableAsync`） |
| A2 | 未标注注解的请求也被边界通道采集 |
| A3 | 伪造 `X-User-Id` 无法影响主体；可信来源被正确采用 |
| A4 | 事务提交产生 `SUCCESS`；**回滚不产生成功记录**且数据确实回滚 |
| A5 | 401/403 记为 `DENIED`、404 记为 `INVALID`（原实现全部漏采） |
| A6 | 7 类敏感值（含嵌套字段、大小写不同的请求头）全部掩码，非敏感字段保留 |
| A7 | 完好链校验通过；篡改任意一条后被检出并**定位到 seq** |
| A8 | 存储持续故障时 `dropped=0`，失败显式进入死信/WAL |
| A9 | 自定义 `PrincipalResolver` 被框架采用 |
| A10 | `/actuator/auditLog` 可输出收录清单与链校验结果 |
| A11 | 突发流量下 `dropped=0` |

排查单个用例的稳定性问题：

```bash
powershell -NoProfile -ExecutionPolicy Bypass -File tools/probe-one-test.ps1 `
  io.github.pkyit.auditlog.core.pipeline.AuditPipelineTest#doesNotLoseDataWhenStoreFails 8
```

---

## 9. 性能

本框架**不宣称**"零开销"。审计必然有成本，正确做法是把成本做成可配置、可度量、可背压。

**已在一台 6C8G 的 openEuler 24.09 服务器上完成实测**，四组对照的完整数据、方法与结论见
[`docs/06-实测报告.md`](docs/06-实测报告.md)。核心实测结论：

| 场景 | Requests/s | P50 | P99 | 相对基线 |
|---|---|---|---|---|
| 基线（零审计） | 25,929 | 6.9 ms | 110 ms | 1.00× |
| 仅边界采集 | 10,983 | 13.2 ms | 550 ms | 0.42× |
| 复现原文章（采集全关） | 10,983 | 13.7 ms | 640 ms | 0.42× |
| 采集全开（内存） | 3,003 | 58.5 ms | 1.11 s | 0.12× |
| 采集全开 + 落盘 + 哈希链 | 2,886 | 56.4 ms | 1.10 s | 0.11× |

两条必须知道的事实：

1. **关掉 `logHeaders`/`logRequestParams`/`logResponseParams` 并不能提升吞吐**
   （上表第 2、3 行几乎完全相同）。这三个开关只决定"载荷要不要序列化并脱敏"，
   而审计记录本身仍会被创建、入队、投递 —— 成本在那儿。把它们当作性能手段是无效的。
2. **采集全开的真实成本约为基线的 9 倍，P99 劣化约 10 倍。**
   对高并发或低延迟敏感的业务，这是必须提前评估的硬代价。
   **性能不是本框架的卖点，四条不变量才是。**
3. **延迟数字必须与并发度一起看**：同样的端点，c=200 时 P99 为 550ms/1.10s，
   c=64 时降到 92ms/251ms —— 高并发下的 P99 主要是**连接排队**，不是审计开销。

### 9.1 已完成的优化（采集全开路径 +19.7%）

基于 async-profiler 火焰图定位（而非猜测），已实施三处优化：

| 优化 | 问题 | 收益 |
|---|---|---|
| **内容正则合并预筛** | 5 条内置正则各自完整扫描一遍文本（占采集 CPU **22.5%**） | 主要收益来源 |
| **派生配置记忆化** | `toFieldRules()`/`toRegexRules()` 每请求重建并**重新编译正则** | 结构收益 |
| **字段路径匹配快速拒绝** | 每个字段 × 每条规则都完整解析路径并分配 `ArrayList` | 结构收益 |

**对照实验结果**（两个真实制品、同口径、每轮唯一端口 + 文件锁）：

| 制品 | 有效样本 | 中位数 | min | max |
|---|---|---|---|---|
| 优化前 | 2 | 3,102 rps | 2,968 | 3,236 |
| **优化后** | 3 | **3,712 rps** | **3,531** | 3,810 |

**优化后的最差一轮（3,531）仍优于优化前的最好一轮（3,236）** ——
样本完全不重叠，因此这是真实位移而非噪声。

⚠️ 这些数字来自一台 6C8G 虚拟机与构造载荷。
**热点位置随负载形态变化，请用你们自己的接口重跑**（脚本与方法见
[`docs/07-性能优化方案.md`](docs/07-性能优化方案.md)）。

### 9.2 最大的优化杠杆不是"让记录更便宜"，而是"少记不需要记的"

实测中"仅边界采集"就要吃掉 58% 吞吐，因为**每个请求**都会产生一条记录。
可选策略（默认保持现状，需显式开启）：
- `NON_SUCCESS_ONLY`：只记录非成功请求（4xx/5xx/异常）+ 全部业务操作；
- `ANNOTATED_ONLY`：只记录标注了 `@AuditLog` 的操作。

在多数合规口径下，这反而是**信噪比更高**的审计。详见
[`docs/07-性能优化方案.md`](docs/07-性能优化方案.md) §3.1。

过载实测（队列压到 2,048、真实流量强制溢出）：
`collect=65,433 = enqueued 7,214 + journaled 58,219`，**dropped = 0** ——
队列真正打满时记录降级写入本地兜底日志，一条不丢。

⚠️ 不要在一个"关闭全部采集开关"的端点上压测然后宣称框架性能 —— 那没有测到采集成本。

---

## 10. 已知局限（请务必阅读）

1. **仅支持 Servlet MVC**：基于 `WebMvcRegistrations`，WebFlux 不适用（边界通道可独立用于 WebFlux）。
2. **响应正文默认不采集**：采集响应正文需要包装 `HttpServletResponse` 输出流，会改变流式/大响应行为，当前只记录响应元信息；如需正文请实现自定义扩展。
3. **语义通道与宿主 `WebMvcRegistrations` 互斥**：同容器只能有一份生效。被覆盖时语义字段缺失，但边界通道仍保证不漏事件（明确降级，非静默丢失）。
4. **默认存储 `logging` 不满足合规留存**：启动会告警，生产请改用 `json-file` 或 `jdbc`。
5. **无密钥哈希链不抗有写权限的攻击者**：需配置 `chain.secret` 或把链头锚定到外部不可变存储。
6. **未提供强一致写库模式**：异步流水线与"业务事务同写"在设计上互斥；如确需强一致，由自定义 `AuditStore` 实现 `supportsSameTransaction()` 自行承担。
7. **多实例写同一 `json-file` 目录需分片**：建议 `file-prefix` 中包含实例 ID；生产建议用 `jdbc` 或消息队列。

---

## 11. 目录结构

```
audit-log/
├── audit-log-core/                    框架内核（不依赖 Servlet）
│   └── api/ model/ mask/ serialize/ chain/ pipeline/ wal/ store/ metrics/
├── audit-log-spring-boot-starter/     Spring Boot 自动装配与采集
│   └── properties/ autoconfigure/ collect/ method/ servlet/ identity/ validation/ actuator/ store/
├── audit-log-samples/                 示例与端到端验收
├── legacy/audit-log-prototype/        原技术原型（仅存档对照，不参与构建）
├── docs/                              评审报告 / 整改方案 / ADR / 迁移指南 / 压测方案
└── tools/build-classpath.ps1          离线环境 classpath 组装脚本
```

---

## 12. 文档索引

| 文档 | 内容 |
|---|---|
| [`docs/01-架构评审报告.md`](docs/01-架构评审报告.md) | 对原实现的架构评审（问题清单与依据） |
| [`docs/02-整改方案.md`](docs/02-整改方案.md) | 目标架构、不变量、SPI 契约、验收标准 |
| [`docs/02b-整改方案自评审.md`](docs/02b-整改方案自评审.md) | 对整改方案自身的对抗性评审（9 处修正） |
| [`docs/03-架构决策记录(ADR).md`](docs/03-架构决策记录(ADR).md) | 关键取舍与代价 |
| [`docs/04-迁移指南.md`](docs/04-迁移指南.md) | 从原型迁移到本版本 |
| [`docs/05-性能验证方案.md`](docs/05-性能验证方案.md) | 四组对照压测设计 |
| [`docs/06-实测报告.md`](docs/06-实测报告.md) | **实测报告**：性能压测 / 安全测试 / 防篡改攻防（含由此发现的框架缺陷） |
| [`docs/07-性能优化方案.md`](docs/07-性能优化方案.md) | **优化方案**：火焰图定位、三处已实施优化、对照实验与测量方法论教训 |
| [`CHANGELOG.md`](CHANGELOG.md) | 版本变更 |

---

## 13. 许可证

[Apache License 2.0](LICENSE)
