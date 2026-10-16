# 变更日志

## [1.0.0] —— 架构评审驱动的重写

本次发布的实质是：**把"技术演示"重建为可被信任的审计组件**。

四条不变量（身份不可伪造、结果真实、事件不丢、历史不可篡改）成为设计约束，
性能不再是首要卖点。

### 新增

**可靠性（不变量 I3）**
- 有界环形队列（`BoundedRingQueue`，无锁、多生产者多消费者）替代无界 `@Async` 队列
- 五种显式背压策略：`SPILL`（默认）/ `BLOCK` / `DROP_OLDEST` / `DROP_NEWEST` / `FAIL_FAST`
- 本地兜底日志（WAL）：独立写线程，**请求线程不做磁盘 I/O**；三级降级全部计数
- 投递失败指数退避重试 + 死信 + 启动重放 + 重放检查点
- `SmartLifecycle` 停机排空队列并 flush WAL（不再丢队列）
- 全部失败路径都有指标：`dropped` / `journaled` / `journal.rejected` / `deadletter` / `backpressure`

**防篡改（不变量 I4）**
- SHA-256 哈希链与 HMAC-SHA256 签名链（`constantTimeEquals` 防比较侧信道）
- `AuditChain#verify` 校验并**定位到具体 seq**
- 链与多消费者不兼容时自动降为单消费者（而不是产生一条必然校验失败的链）

**身份（不变量 I1）**
- `PrincipalResolver` SPI 链式解析（认证上下文 / 请求头 / 匿名）
- `PrincipalType` 标注身份来源可信度；请求头来源默认关闭且标记为 `TRUSTED_HEADER`
- `ClientIpResolver`：默认**不信任** `X-Forwarded-For`，仅可信代理网段内才采纳
- 租户与链路 ID 解析（支持 W3C `traceparent`）

**结果真实性（不变量 I2）**
- `TransactionAuditTracker` 在 `afterCompletion` 捕获真实事务结果
- 默认 `drop-on-rollback=true`：回滚不产生记录（避免"记录了没发生的事"）
- 状态码取自真实响应；`Outcome` 枚举区分成功/失败/拒绝/无效/回滚
- `ErrorInfo` 记录异常类型 + 消息 + 根因链（堆栈默认关闭）

**采集覆盖**
- 双通道：Filter 边界通道（401/403/404/绑定失败全覆盖）+ HandlerAdapter 语义通道
- 单记录两阶段填充，边界通道唯一一次发布（消除合并竞态）
- 请求体缓存（可配置上限），默认**不序列化方法实参**（避免懒加载/循环引用/框架参数噪音）

**脱敏**
- 大小写不敏感（修复原实现用小写黑名单匹配 `Authorization` 的令牌泄漏）
- 递归 JSON 字段路径脱敏（修复原实现完全不管报文体）
- 内容级正则脱敏（Bearer/身份证/银行卡/手机号/邮箱）
- `Authorization`/`Cookie` 等核心黑名单**恒生效且不可被配置覆盖**

**可插拔与工程化**
- 拆分为 `audit-log-core` / `audit-log-spring-boot-starter` / `audit-log-samples` 三模块
- 标准自动配置（`AutoConfiguration.imports`），**引入依赖即生效，无需注解**
- 全量配置模型 `AuditLogProperties`（`audit.log.*`）
- 11 个 SPI 扩展点，全部 `@ConditionalOnMissingBean`
- 启动校验：注解标注却不采集 → **默认启动失败**；非合规存储/未启用链/会丢数据的策略 → 逐条告警
- Actuator：健康检查（队列积压/丢弃/存储不可用 → DOWN）、指标端点、诊断端点
- `JdbcAuditStore`（含建表与索引参考）、`JsonFileAuditStore`（轮转 + 保留策略）
- **Apache-2.0 LICENSE**（原仓库未声明许可证，实际无使用授权）
- 完整 POM 元数据（`licenses` / `developers` / `scm` / `url`）、版本 `1.0.0`（不再是 SNAPSHOT）

**验证**
- 应用内端到端验收 11 项（A1–A11），以退出码反映结果，可直接用于 CI
- 覆盖真实 HTTP、真实事务、真实存储故障注入、真实链篡改检测
- **单元测试 62 项**（`audit-log-core` 55 + starter 7），覆盖字段路径匹配、脱敏、
  有界队列并发、哈希链、流水线可靠性与采集层异常隔离
- 提供离线测试启动器（`tools/run-tests.ps1` + `JUnitConsoleRunner`）：
  直接调用 JUnit Platform Launcher，绕开缺失的 surefire provider；
  联网环境恢复 `maven-surefire-plugin` 即等价于 `mvn test`
- 提供单用例稳定性探针 `tools/probe-one-test.ps1`

### 实施期由测试发现的真实缺陷（均已修复）

这些是**静态评审看不出来、只有真跑才会暴露**的问题，记录在此以免回归：

| 缺陷 | 后果 | 修复 |
|---|---|---|
| 死信指标被双重计数（`AuditPipeline` 与 `FileAuditJournal` 各加一次） | 指标翻倍（17 条计成 34），**"不丢数据"从此无法用数据证明** | 确立指标单一归属约定（ADR-013） |
| `dropped` 指标同样双重计数 | 同上 | 同上 |
| 重试退避被中断即放弃整批 | 停机瞬间**静默丢掉正在重试的一批记录**（实测 20 条只落 17 条死信） | 退避改为不可中断的 `parkNanos`；即使被中断也先落死信再退出（ADR-014） |
| 注入 `RequestMappingHandlerMapping` 提前触发 WebMvc 初始化 | 循环依赖 → **应用无法启动** | 注册表改为容器刷新后填充（ADR-009） |
| `getIfUnique()` 在引入 Actuator 后返回 null | 注册表为空 → **注解全部静默失效** | 按 Bean 名确定性选取映射 |
| 默认 include 模式写成 `/*` | Ant 语法只匹配单层路径 → 过滤器**静默跳过所有多层级接口** | 默认改为 `/**`，并加启动校验拦截该写法 |
| 全局 `sensitive-fields` 未传入采集计划 | 配置的敏感字段完全不生效 | 合并全局规则与方法级规则 |
| glob 编译成正则导致 `$.token` 匹配不到 `$.nested.token` | **配置了敏感字段却依然原文落库**（静默泄漏） | 重写为 `FieldPathMatcher` 段匹配（ADR-006） |
| `List<PrincipalResolver>` 作为 Bean 注入 | 使用者提供自定义解析器时注入歧义 → 可能启动失败 | 解析器链改为就地构建 |
| 测试用 `ThreadLocal` 传递身份 | 在 Tomcat 工作线程下失效（与原框架同类错误） | 示例改为请求头传递，并写明原因 |


### 修复（相对 `legacy/audit-log-prototype`）

| 问题 | 说明 |
|---|---|
| 身份可伪造 | `request.getHeader("userId")` 改为认证上下文解析 |
| 主键硬编码 | `new AuditLogDO(121212L, ...)` 改为全局唯一 ID |
| 回滚记成成功 | 事务结果对齐；默认回滚不产生记录 |
| 状态码靠猜 | `exception != null ? 500 : 200` 改为读真实响应 |
| 认证失败/403/404 漏采 | 新增边界通道覆盖 |
| 令牌泄漏 | 头部匹配大小写不敏感 + 核心黑名单不可覆盖 |
| 报文体不脱敏 | 递归字段路径 + 内容正则 |
| 无持久化/可静默丢弃 | 有界队列 + WAL + 重试 + 死信 + 指标 |
| 静默失效 | 启动校验 + 未命中指标 + 收录清单端点 |
| 禁用功能反而启动失败 | 移除字符串 `getBean` + 强制转型 |
| 依赖非稳定内部 API | 保留 HandlerAdapter 但降级路径明确；`doInvoke` 异常不再覆盖业务异常 |
| 枚举/`toString()` 非结构化转储 | 改为结构化 `Payload` 快照 |
| 自建类路径扫描（可致启动失败、不识别继承注解） | 改为基于 Spring 真实 HandlerMapping |
| 无配置模型 | 引入 `AuditLogProperties` |
| README 与代码不一致 | 文档重写并与代码对齐 |
| 无许可证 | 补 Apache-2.0 |

### 已知局限

见 README 第 10 节（仅 Servlet MVC、响应正文默认不采集、语义通道与宿主 `WebMvcRegistrations` 互斥、
默认存储非合规、无密钥链的边界、不提供强一致写库、多实例文件存储需分片）。

### 升级注意（破坏性变更）

1. 包名由 `com.github.pkyit.module.audit` 改为 `io.github.pkyit.auditlog.*`
2. 身份来源默认不再读请求头（需要时显式开启 `identity.header-principal-enabled`）
3. 事务回滚默认不产生记录（需要时设 `drop-on-rollback=false`）
4. `logResponseParams` 默认由 `true` 改为 `false`
5. `AuditLogDO` 不再保留兼容别名，字段映射见迁移指南第 3.4 节

详见 [`docs/04-迁移指南.md`](docs/04-迁移指南.md)。
