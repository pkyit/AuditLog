# 13 - 双 Web 环境自适配：实测报告

**验证目标**：同一份 `audit-log` 代码，在 **Servlet（Tomcat）** 与 **Reactive（WebFlux/Netty）**
两种 Web 环境下都能自适应运行，且**审计语义完全一致**。

**结论**：达成。两种环境均通过全部验证；Servlet 侧无回归。

---

## 一、验证矩阵

| 验证项 | Servlet(Tomcat) | Reactive(Netty) |
|---|---|---|
| 应用启动 | ✅ `Tomcat started on port 8083` | ✅ `Netty started`，环境自检 `REACTIVE 且过滤器已注册` |
| 单元测试 | ✅ 72 + 33 = **105/105** | 同一套（共用采集编排） |
| 端到端验收 | ✅ **11/11** | — |
| 真机采集 | ✅ **11/11** | ✅ **6/6 条记录** |
| 语义通道 `@AuditLog` | ✅ 已有用例覆盖 | ✅ **7/7** |
| 普通返回 | ✅ | ✅ `plain` |
| 业务异常 | ✅ | ✅ `error` → `httpStatus=500`、`outcome=FAILURE` |
| 未匹配路由 404 | ✅ | ✅ `httpStatus=404`、`outcome=FAILURE` |
| 慢响应耗时 | ✅ | ✅ `slow-600ms` → `duration=615ms` |
| 异步 / 流式（SSE） | ✅ 11 项异步用例 | ✅ `stream`（`Flux`，3×80ms） |
| 请求体采集 | ✅ | ✅ 且业务仍读到完整体（回显 `orderId=WF-1`） |
| 脱敏 | ✅ | ✅ `Authorization` 未明文落库 |
| 哈希链 | ✅ | ✅ 8/8 带链哈希，`seq` 无重复 |
| 客户端断开（取消） | ✅ | ✅ 有兜底收尾路径 |

**测试环境**：真实服务器 `10.10.10.103`（openEuler 24.09，6C/7.3G），
Java 17，真实 HTTP 请求 + 真实落盘记录（不是 mock）。

---

## 二、Reactive 侧实测记录（原始数据）

```
容器: Netty=True Tomcat=False
环境自检: REACTIVE 且过滤器已注册
  plain      HTTP 200
  slow       HTTP 200
  error      HTTP 500
  404        HTTP 404
  stream     HTTP 200
  post       HTTP 200  业务读完整体=True

审计记录数 = 6（期望 6）
  uri=/reactive/plain        status=200   duration=19     outcome=SUCCESS  seq=1
  uri=/reactive/slow/600     status=200   duration=616    outcome=SUCCESS  seq=2
  uri=/reactive/error        status=500   duration=40     outcome=FAILURE  seq=3
  uri=/reactive/not-exist    status=404   duration=7      outcome=FAILURE  seq=4
  uri=/reactive/stream       status=200   duration=91     outcome=SUCCESS  seq=5
  uri=/reactive/post         status=200   duration=37     outcome=SUCCESS  seq=6
```

语义通道（真机，`POST /reactive/annotated?orderId=ORD-9`）：

```
通过 7 项，失败 0 项
  [通过] annotated 记录数=1
  [通过] operation=创建反应式订单
  [通过] actionType=MUTATE
  [通过] resourceType=reactive-order
  [通过] resourceId=ORD-9（按 resourceIdParam 从查询参数解析）
  [通过] 自定义标签 biz=webflux
  [通过] 未标注端点 operation 为空（仅边界兜底）
```

Servlet 侧（同一台机器）：

```
Tomcat started on port 8083 (http) with context path '/'
结果：通过 11 项，失败 0 项
[audit-acceptance] 退出码 = 0（通过 11 项，失败 0 项）
```

---

## 三、实测发现的缺陷与处置

按发现顺序记录。**每一条都是靠实测数据定位的，没有一条是靠推理"看出"的。**

| # | 现象 | 根因 | 处置 |
|---|---|---|---|
| 1 | WebFlux **启动失败**（`NoClassDefFoundError: RequestMappingHandlerMapping`） | 自动配置的**注册粒度**问题：`AuditMethodRegistry` 等被注册为顶层 Bean，类签名引用 servlet 类型；`@ConditionalOnClass` 保护不了"其他位置上直接引用该类型"的 Bean 方法 | 注册表拆成接口 + servlet 实现，采集相关 Bean 全部移入环境条件配置 |
| 2 | Servlet 侧 10 个用例失败 | 默认空注册表用 `@ConditionalOnMissingBean` 与 Servlet 侧具体注册表**互相抑制** | 改用 `@ConditionalOnMissingClass("jakarta.servlet.Filter")` 按类路径分流 |
| 3 | 404 / 业务异常被记成 `httpStatus=null` | WebFilter 返回的 Mono 在**响应写出之前**就完成了，那一刻状态码还是空 | 进入过滤器时**立即注册** `beforeCommit` 回调，在提交时取最终状态码并收尾 |
| 4 | 只有 error/404 被记录，其余 4 条全漏 | 改成"出错后再补注册 `beforeCommit`"，成功路径的提交已经错过 | 同 #3：必须**进入时就注册** |
| 5 | 流式响应耗时偏小（3×80ms 的 SSE 记成 ~91ms） | 提交时刻≈响应头写出时刻，记录到的是**首字节耗时** | **刻意取舍并如实记录**：状态码正确性优先于流式耗时精度 |
| 6 | 语义字段全空（`operation=null`） | 读 `BEST_MATCHING_HANDLER` 属性读不到（属性写在 `mutate()` 出的另一个 exchange 上） | 改为包装 `HandlerAdapter` |
| 7 | `mutate().attributes(...)` 编译失败 | Spring 6.2 的 `ServerWebExchange.Builder` **没有**该方法 | 放弃共享属性表方案 |
| 8 | `asReadOnlyBuffer()` / `mark()` / `reset()` 编译失败 | 这些方法在 `DataBuffer` 上不存在 | 改用 `toByteBuffer(pos, pos+n)`：按绝对位置复制，**不移动读位置** |
| 9 | `resourceId` 恒为 `null` | 过滤器与适配器**两处**都做语义填充，"先到者胜"而先到的那次拿不到请求 | 同一职责只保留一处（适配器），并用记录标签 `diagReqNull=true` 定位 |
| 10 | 语义仍然全空（包装后） | 注册表是 `AuditMethodRegistry.empty()`，而 `AuditCaptureService` 是**通过注册表**取元数据的 | 改为 `ResolvingAuditMethodRegistry`（请求期解析 + 缓存） |
| 11 | 构建机构建失败 | Maven **不允许同一依赖声明两次**，后者覆盖前者：重复声明把 `optional` 依赖降级为 test 作用域，主源码编译反而看不到 | 删除重复声明（`optional` 对本模块编译与测试本来可用） |
| 12 | 服务器上仍报 `NoClassDefFoundError` | classpath 解析到 `~/.m2` 里**改造前的旧 starter jar** | `mvn install` 刷新本地仓库；用 `javap` 确认后重跑 |

另有两条**工具/流程**教训：

- `mvn install -Dmaven.test.skip=true` **会跳过测试编译**。测试由独立 JUnit 运行器执行，
  于是测试字节码停留在旧版本，表现为 `IncompatibleClassChangeError`
  （"must be InterfaceMethodref constant"），一度被误判为条件注解写错。
  **改动类型定义后必须强制重编译测试类。**
- `System.out` 在进程存活期间是**块缓冲**的，重定向到文件后读不到。
  诊断信息应写进**可读取的业务产物**（本次写进审计记录标签）或等进程退出后再读。

---

## 四、已知局限（如实声明，未实现）

1. **流式响应耗时记的是首字节耗时**，不是整个流结束耗时（见缺陷 #5）。
   要让流式耗时也精确，需要包装响应体数据流在完成时收尾，
   代价是复杂度与"响应已提交后无法再改"的约束。
2. **响应正文采集仍未实现**（两种环境都是）：只记录响应元信息（状态码）。
   这是既有局限，与本次改造无关。
3. **客户端断开（cancel）路径**有兜底收尾，但未做专项压力验证。
4. **Reactive 语义通道依赖 `BeanPostProcessor` 包装适配器**：
   `DispatcherHandler` 无 setter（只有 `()` 与 `(ApplicationContext)` 两个公开构造器），
   无法注入自定义适配器列表。若未来 Spring 改变适配器收集方式，
   该通道可能失效 —— 已通过日志告警而非静默降级来防这一点。
5. 未验证 **RouterFunction**（函数式路由）风格的端点语义采集；
   当前只包装 `RequestMappingHandlerAdapter`。

---

## 五、可复现的验证脚本

| 脚本 | 用途 |
|---|---|
| `tools/remote/50-verify-webflux.py` | 本地/远程启 WebFlux 宿主，25 项断言（边界+语义+状态码+耗时+脱敏+链） |
| `tools/remote/51-verify-webflux-server.py` | 在服务器上以真机方式验证 WebFlux 采集 |
| `tools/remote/52-verify-servlet-server.sh` | 在同一台机器上验证 Servlet 端到端验收 |
| `tools/remote/53-verify-webflux-semantic.py` | 专门核对 Reactive 语义通道（7 项） |
| `tools/run-tests.ps1` | 离线运行单元测试（JUnit Platform Launcher） |
