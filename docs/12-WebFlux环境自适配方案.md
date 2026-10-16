# 12 - WebFlux/Reactive 环境自适配：可行性与方案

**问题（使用者提出）**：框架只在默认的 Servlet（Tomcat）环境下验证过。
换成反应式（WebFlux/Netty）会不会直接启动报错？能否做到"Web 容器无感、自适配"？

**结论先说**：**问题成立，且我复现了启动失败。** 但根因与最初猜想不同（不是"硬依赖 starter-web"），
修复路径清晰，**不需要改动 `audit-log-core`**（它已经是 web 无关的）。

---

## 一、先证明问题（实测，不靠推理）

新建最小 WebFlux 宿主 `audit-log-samples-webflux`（只依赖 `spring-boot-starter-webflux` +
本框架 starter，**刻意不引入 `spring-boot-starter-web`**），直接启动，得到：

```
Caused by: java.lang.IllegalStateException:
    Failed to introspect Class [io.github.pkyit.auditlog.spring.method.AuditMethodRegistry]
Caused by: java.lang.NoClassDefFoundError:
    org/springframework/web/servlet/mvc/method/annotation/RequestMappingHandlerMapping
```

**启动直接失败**，与使用者判断一致。

### 1.1 根因（与最初猜想不同，值得记录）

先纠正一个我一开始也搞错的点：**starter 并没有硬依赖 `spring-boot-starter-web`** ——
`spring-webmvc` / `jakarta.servlet-api` 在 pom 里都是 `optional=true`，
实测 WebFlux 应用的 classpath 上**既没有 tomcat-embed-core 也没有 jakarta.servlet-api**。
所以"把 Tomcat 强行拉进来"这个担心不成立（这点设计是对的）。

真正的根因是 **自动配置的注册粒度问题**：

`AuditLogAutoConfiguration` 已经给嵌套配置加了正确的条件保护：

```java
@ConditionalOnClass(name = "jakarta.servlet.Filter")
@ConditionalOnWebApplication(type = SERVLET)
public static class ServletBoundaryConfiguration { ... }
```

**但 `auditMethodRegistry` 是顶层 `@Bean`**，而 `AuditMethodRegistry` 类的签名里直接引用了
`RequestMappingHandlerMapping`（servlet 专属类型）：

```java
public void populate(RequestMappingHandlerMapping handlerMapping, boolean enabled) { ... }
```

Spring 在**内省 `auditMethodRegistry` 这个 bean 定义**时就必须解析 `AuditMethodRegistry` 类，
于是触发 `NoClassDefFoundError`。

**这是一个通用陷阱**：`@ConditionalOnClass` 保护的是**被标注的那个配置类**，
保护不了**其他位置上直接引用该类型的 bean 方法**。
（同类问题在社区里也出现过：`@ConditionalOnClass` 的字符串写法就是为了避免在
自动配置类里直接引入可选依赖 —— 参见 [camunda 的同类修复](https://github.com/camunda/camunda/pull/47466)。）

### 1.2 耦合面清单

| 位置 | 耦合类型 | 是否阻碍 reactive |
|---|---|---|
| `audit-log-core` | **完全无 web 依赖** | ✅ 无需改动（关键资产） |
| `AuditMethodRegistry` | `RequestMappingHandlerMapping` / `RequestMappingInfo` | ❌ 类签名即引用 |
| `AuditRecordContext` | **字段持有 `HttpServletRequest` / `HttpServletResponse`** | ❌ 最深的耦合 |
| `AuditCaptureService` | `HttpServletRequest` / `Cookie` / `HandlerMethod` | ❌ 采集编排与 Servlet 绑死 |
| `AuditLogServletFilter` | `jakarta.servlet.Filter` | ❌ servlet 专属 |
| `CachedBodyHttpServletRequest` | `jakarta.servlet` | ❌ servlet 专属 |
| Actuator 端点 | 与 web 类型无关（Boot 会按环境选 reactive 变体） | ✅ 基本可用 |

**关键结论**：`audit-log-core`（流水线、背压、WAL、脱敏、哈希链）**一行都不用改**。
需要改的是 **starter 这一层**：把"采集编排"从 Servlet 类型上解耦，然后为两种环境各做一个适配器。

---

## 二、可行性分析（逐个技术点）

### 2.1 `@ConditionalOnWebApplication` 的三个 type —— 可用，但**不能作为唯一判据**

| 取值 | 判定依据 |
|---|---|
| `SERVLET` | 存在 `jakarta.servlet.Servlet` **且** `ConfigurableWebApplicationContext` |
| `REACTIVE` | 存在 `org.springframework.web.reactive.DispatcherHandler` **且** web 上下文 |
| `ANY`（默认） | 是 web 应用即可 |

用它做**环境判定**是对的，但实践上要**两个条件同时用**：

```java
@ConditionalOnWebApplication(type = SERVLET)     // 决定"我是哪种环境"
@ConditionalOnClass(name = "jakarta.servlet.Filter")  // 决定"依赖是否真的在"
```

理由：`@ConditionalOnWebApplication(SERVLET)` 依赖 `Servlet` 类存在才能判定，
而**字符串形式的 `@ConditionalOnClass` 用 ASM 读字节码、不会加载类**，
是最安全的保护手段。两者结合才能既正确又不触发 `NoClassDefFoundError`。

### 2.2 WebFlux 的边界通道：`WebFilter`

- `org.springframework.web.server.WebFilter` 是 WebFlux 的过滤器 SPI，
  以 `@Bean` 形式注册即可，**无需 `FilterRegistrationBean`**；
- 顺序通过 `@Order` 或实现 `Ordered`；Spring Security 反应式链默认 order 为 `-100`，
  与 servlet 侧同理，我们取 **-150** 保证"认证失败也能被记录"；
- 取响应状态：`ServerHttpResponse.getStatusCode()`（`HttpStatusCode`）；
- 取请求体：**WebFlux 的请求体是一次性 `Flux<DataBuffer>`**，
  要既能给业务读、又能给审计留一份，必须用
  `ServerHttpRequestDecorator` 包装 `getBody()`，在 `Flux` 上挂 `doOnNext` 旁路拷贝，
  再用 `exchange.mutate().request(decorator).build()` 换掉 exchange
  —— 这与 servlet 侧 C1 修复的 "tee（透传 + 旁路缓存）" 是**同一个思路的两种实现**。
- **收尾时机**：`chain.filter(exchange).doFinally(signal -> finish(...))`。
  `doFinally` 在**正常完成、异常、取消**三种情况下都会触发，正好覆盖
  "业务异常 / 客户端断开 / 正常返回"。

### 2.3 WebFlux 的语义通道：如何拿到 `@AuditLog`

- WebFlux 的 handler 同样是 `org.springframework.web.method.HandlerMethod`（`spring-web`，**非 servlet**）；
- 但 `RequestMappingHandlerMapping`（servlet 的 `getHandlerMethods()`）在 WebFlux 不可用。
  WebFlux 的对应实现是 `RequestMappingHandlerMapping`（**不同包**：
  `org.springframework.web.reactive.result.method.annotation`），
  它的 handler 注册表**没有公开 `getHandlerMethods()`**。

  **因此语义通道换个做法**：不在启动时全量扫描，而是在**请求期**从
  `ServerWebExchange` 里取出已经解析好的 `HandlerMethod`：

  ```java
  Object handler = exchange.getAttribute(HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE);
  ```

  这个属性由 WebFlux 的 `AbstractHandlerMapping` 在匹配阶段写入，
  值是 `HandlerMethod`（最优匹配）。于是：
  - 不需要访问非公开 API；
  - 不需要启动扫描，天然支持运行时新增路由；
  - 类级 `@AuditLog` 回退（H4 的修复）可以**完全复用同一段注解解析逻辑**。

### 2.4 跨阶段传递审计上下文：**不能用 ThreadLocal**

这是反应式最本质的差异，也是必须专门设计的点：

- Servlet：一个请求固定在一个线程上，`ThreadLocal` 安全；
- WebFlux：一个请求会在**多个线程/事件循环**间流转（`subscribeOn`/`publishOn`/`delayElements`
  都会切线程），`ThreadLocal` **不可靠**。

**方案：用 `ServerWebExchange.getAttributes()`**。

```java
// WebFilter 中
exchange.getAttributes().put(AUDIT_CONTEXT_KEY, context);
// 语义通道 / 收尾时
AuditContext ctx = (AuditContext) exchange.getAttributes().get(AUDIT_CONTEXT_KEY);
```

`getAttributes()` 返回的 map 与 exchange 同生命周期、可变，且随 `exchange.mutate()` 保留，
是 WebFlux 里传递请求级状态的**标准做法**。相比 Reactor `Context`
（不可变、需要 `contextWrite`/`deferContextual` 逐层传递），它的优点是：
不需要改写业务链路、不受算子切线程影响。

### 2.5 流式响应（SSE / `Flux`）

- `doFinally` 在 `Flux` 全部元素产出后触发，因此**收尾时机天然正确**；
- 但要注意：`doFinally` 里此时读 `response.getStatusCode()` 可能仍是未提交状态，
  应以"已写出的状态码或默认值"为准，并**如实记录**（不猜）。

### 2.6 需要确认的外部事实

以下两条我**没有靠记忆下结论，而是列为待实测项**：
1. WebFlux 下 `HandlerMapping.BEST_MATCHING_HANDLER_ATTRIBUTE` 是否稳定为 `HandlerMethod`；
2. `doFinally` 在"客户端主动断开（cancel）"时是否确实触发。
   —— 这两点会在实测阶段用日志/断言固定下来。

---

## 三、方案设计

### 3.1 模块结构（新增两个适配模块）

```
audit-log-core                    （不动：流水线/背压/WAL/脱敏/哈希链/存储 SPI）
audit-log-capture                 （新：环境无关的采集编排与上下文）
audit-log-spring-boot-starter     （改：只留"公共装配 + Servlet 适配"）
audit-log-spring-boot-starter-webflux （新：Reactive 适配）
```

> 备选：也可以把 reactive 适配放进现有 starter、用条件注解隔离。
> **选择拆模块的理由**：① 让"引入哪个依赖 = 支持哪种环境"一目了然；
> ② 避免 WebFlux 用户被动引入 servlet 相关的类；
> ③ 与 Spring Boot 官方（`spring-boot-starter-web` vs `spring-boot-starter-webflux`）的惯例一致。
> **但保留兼容**：现有 starter 继续同时支持 servlet，且新增一个
> `audit-log-spring-boot-starter-all`（或直接让现有 starter 按环境条件装配）
> 以免破坏已有使用者。

⚠️ **兼容性约束（重要）**：现有使用者引入的是 `audit-log-spring-boot-starter`。
不能让他们升级后需要改依赖坐标。因此**现有 starter 必须继续工作**，
reactive 支持最好是以"同一个 starter 按环境自适配"的形式提供，
即：`audit-log-spring-boot-starter` 内部按条件装配 servlet 或 reactive 适配。

### 3.2 核心解耦：`AuditRecordContext` 不再持有 Servlet 对象

这是最关键、也是改动最大的一步。当前：

```java
public class AuditRecordContext {
    private final HttpServletRequest request;      // ← 阻碍 reactive
    private final HttpServletResponse response;
}
```

改为持有**环境无关的请求快照**：

```java
public interface AuditRequestView {
    String method();
    String uri();
    String queryString();
    String header(String name);          // 头读取
    String clientIp();
    Map<String, String> headers();       // 供脱敏
    String cachedBody();                 // 已按上限截断的请求体（无则 null）
    Object nativeRequest();              // 逃生舱：适配器自用，编排层不依赖
}
```

Servlet 适配器用 `CachedBodyHttpServletRequest` 实现它；
Reactive 适配器用 `ServerWebExchange` + `ServerHttpRequestDecorator` 实现它。

`AuditCaptureService` 的编排逻辑（约 130 行 `finish()`）改为只依赖
`AuditRequestView` + `responseStatus` + `elapsed` + `error`，
即可被两个适配器共用。

### 3.3 环境适配器职责对照

| 能力 | Servlet 适配 | Reactive 适配 |
|---|---|---|
| 过滤器 SPI | `OncePerRequestFilter`（order -150） | `WebFilter`（order -150） |
| 请求体 tee | `CachedBodyHttpServletRequest` | `ServerHttpRequestDecorator` + `DataBuffer` 旁路拷贝 |
| 上下文承载 | 请求属性 + `ThreadLocal` | `ServerWebExchange.getAttributes()` |
| 语义通道 | `RequestMappingHandlerAdapter` 子类 | 请求期读 `BEST_MATCHING_HANDLER_ATTRIBUTE` |
| 收尾时机 | `finally` + ASYNC dispatch | `doFinally` |
| 客户端 IP | `getRemoteAddr` + 可信 XFF | 同上（读 `ServerHttpRequest`） |
| 异步/流式 | ASYNC dispatch（已修复） | `Flux` 的 `doFinally` |

### 3.4 需要防的"静默失效"（本框架的核心关注点）

新环境最容易出的问题不是报错，而是**能启动但零采集**。因此：

1. **启动自检**：若判定为 REACTIVE 却没有任何 `WebFilter` 被注册 →
   启动告警（甚至失败），绝不允许"安静地不采集"；
2. **启动日志必须打印实际选中的环境**：`审计运行环境 = SERVLET / REACTIVE`；
3. 若同一个应用里**同时**存在 servlet 与 reactive 特征（理论上 Boot 会二选一，
   但要如实告警而不是猜）；
4. `actuator` 端点要能反映"当前生效的适配器"。

---

## 四、方案自评审

### 4.1 这个方案会不会过度设计？

**会，如果只为了"能启动"。** 最小修复其实只要两步：
① 把 `auditMethodRegistry` 的 `RequestMappingHandlerMapping` 参数挪进嵌套条件配置；
② 加 reactive 的 `WebFilter`。
但这样只能得到"WebFlux 下不报错**但完全不采集**"——比启动失败更糟（静默失效）。
所以本方案的重点恰恰是**让 reactive 真正采集**，这是必要的复杂度。

### 4.2 风险清单

| 风险 | 影响 | 缓解 |
|---|---|---|
| 拆模块破坏既有依赖坐标 | 使用者升级即失败 | **现有 starter 保持可用**，只做内部条件装配；新增能力不要求改坐标 |
| `AuditRecordContext` 重构面大 | 可能引入回归 | 先补测试再改；servlet 侧 99 项单测 + 11 项验收作为安全网 |
| reactive 上下文用 exchange attributes 是否足够 | 若某算子脱离原 exchange 会丢上下文 | 实测覆盖 `delayElements`（切线程）、`subscribeOn`、错误链路 |
| 请求体 tee 在 reactive 下内存风险 | `DataBuffer` 需显式释放，泄漏会 OOM | 拷贝后 `DataBufferUtils.release`；上限复用同一配置项 |
| `doFinally` 与状态码的时序 | 可能记到未提交状态 | 实测固定行为；拿不到就如实置空（沿用 L1 的处理原则） |

### 4.3 明确**不做**的事

- **不**改写 core 的流水线/背压/存储（它们与环境无关，且已被实测验证）；
- **不**为 reactive 单独做一套脱敏/链（复用同一实现，保证两种环境**审计语义一致**）；
- **不**支持"同一进程内同时跑 servlet 与 reactive 两套采集"（Boot 本身二选一，跟随即可）。

### 4.4 验收标准（先定，后实施）

1. WebFlux 应用**能启动**（Netty），且**没有任何 audit 相关的报错**；
2. WebFlux 下**边界记录真正产生**：普通返回、异常、404、SSE 流式四类都可采集；
3. WebFlux 下**语义通道生效**：`@AuditLog` 的操作名/资源等字段被填入（含类级回退）；
4. 两种环境下**脱敏行为一致**（同一组敏感字段用例，结果相同）；
5. 两种环境下**哈希链都可用**，且 `canonicalContent` 一致（同一逻辑记录摘要相同）；
6. Servlet 侧**现有 99 项单测 + 11 项 E2E 全部仍然通过**（无回归）；
7. 两种环境都能在**真实服务器**上跑起来并通过上述验证。

---

## 五、实施进度

### 第一阶段（已完成）：让 WebFlux 能启动

**成果**：WebFlux 应用现在可以正常启动。

```
o.s.b.web.embedded.netty.NettyWebServer : Netty started on port 19600 (http)
i.g.p.a.s.w.WebFluxSampleApplication    : Started WebFluxSampleApplication in 2.067 seconds
```

**改动清单**：

1. **`AuditMethodRegistry` 从具体类改为接口**（契约 Web 无关，只暴露元数据查询）；
   原来的扫描实现移到 **`ServletMethodRegistry`**，只被带环境条件的配置引用。
   这解决了 `NoClassDefFoundError` 的根因 —— 顶层 bean 不再引用 servlet 类型。
2. **新增"非 Servlet 环境的默认空注册表"**，用
   `@ConditionalOnMissingClass("jakarta.servlet.Filter")` 分流。
   ⚠️ 这里踩过一个坑：最初用 `@ConditionalOnMissingBean`，结果它与 Servlet 侧的具体注册表
   **互相抑制**，导致 Servlet 环境反而拿到空注册表、10 个用例失败。
   改用"类路径上有没有 servlet"分流后，两者永不共存，问题消失。
   —— 这也是一条值得记住的经验：**条件注解用错方向会造成"反向失效"**。
3. **`AuditLogStartupValidator` 的 bean 移入环境条件配置**
   （它引用了 `RequestMappingHandlerMapping`，同样不能在 WebFlux 下注册）。
4. 新增 `audit-log-samples-webflux` 模块作为 WebFlux 宿主示例
   （含普通/流式/异常/POST 端点，用于后续验证）。

**回归验证**：Servlet 侧 **99 项单元测试 + 11 项端到端验收全部通过**（无回归）。

**另一条独立教训**：`mvn install -Dmaven.test.skip=true` **会跳过测试编译**，
而我的测试是用独立的 JUnit 运行器跑的，于是测试字节码停留在旧版本，
表现为 `IncompatibleClassChangeError`（"must be InterfaceMethodref constant"），
一度让我误以为是条件注解写错。**改动类型定义后必须强制重编译测试类。**

### 第二阶段（已完成）：让 WebFlux 真正采集

**成果**：WebFlux 环境下**真实采集到记录**，且状态码、耗时、脱敏、哈希链全部正确。

真机（10.10.10.103）实测结果：

```
容器: Netty=True Tomcat=False
环境自检: REACTIVE 且过滤器已注册
  plain HTTP 200 / slow HTTP 200 / error HTTP 500 / 404 HTTP 404 / stream HTTP 200
  post  HTTP 200  业务读完整体=True

审计记录数 = 6（期望 6）
  uri=/reactive/plain      status=200  duration=16   outcome=SUCCESS  seq=1
  uri=/reactive/slow/600   status=200  duration=615  outcome=SUCCESS  seq=2
  uri=/reactive/error      status=500  duration=38   outcome=FAILURE  seq=3
  uri=/reactive/not-exist  status=404  duration=7    outcome=FAILURE  seq=4
  uri=/reactive/stream     status=200  duration=91   outcome=SUCCESS  seq=5
  uri=/reactive/post       status=200  duration=36   outcome=SUCCESS  seq=6
```

**核心解耦**（让两种环境共用同一份审计语义）：

| 组件 | 作用 |
|---|---|
| `AuditRequestView` / `AuditResponseView` | 环境无关的请求/响应视图契约 |
| `ServletAuditRequestView` / `ServletAuditResponseView` | Servlet 实现（薄封装） |
| `ReactiveAuditRequestView` / `ReactiveAuditResponseView` | WebFlux 实现 |
| `ActionTypes` | 动作类型解析（从 servlet 专属类里剥出来） |

`AuditCaptureService` 现在只依赖视图接口 —— **脱敏、截断、主体解析、结果判定在两种环境下
是同一份代码**，因此不存在"换容器导致审计语义漂移"的可能。

### 2.1 反应式适配中实测发现的三个坑（都不是靠推理能得出的）

**坑 1：收尾时机不能在 `doFinally`，也不能在 `beforeCommit`，必须"提前注册、提交时收尾"。**

- 在 `doFinally` 收尾 → 状态码还是空。实测：**404 被记成 `httpStatus=null`**（应记 404），
  业务异常同理（应记 500）。因为 WebFilter 返回的 Mono 在响应写出**之前**就完成了。
- 改成"出错后再补注册 `beforeCommit`" → **只有 error/404 两条被记录，其余 4 条全漏**。
  因为成功路径的提交发生在框架的 `handleResult` 里，等出错才注册已经错过提交点。
- 正确做法：**进入过滤器时立即注册 `beforeCommit` 回调**，在提交那一刻取状态码并收尾。

**坑 2：流式响应的耗时语义。**

提交时刻≈响应头写出时刻，因此流式响应记录到的是**首字节耗时**而非整个流的耗时
（实测 3×80ms 的 SSE 记成 ~91ms，实际约 240ms）。
这是**刻意取舍并如实记录**：状态码正确性的优先级高于流式耗时精度 ——
记错状态码会误导下游统计，耗时偏小只是精度问题。

**坑 3：请求体旁路拷贝必须用 `toByteBuffer(pos, pos+n)`。**

`DataBuffer.read(byte[])` 会推进读位置，直接把业务的请求体吃掉一块；
而 `asReadOnlyBuffer()`/`mark()`/`reset()` 在这些 Spring 版本上**并不存在**
（我先按记忆写了 `asReadOnlyBuffer`，编译直接失败）。
最终用 `toByteBuffer(readPosition, readPosition + n)` —— 按绝对位置复制，不动读位置。
验证方式：控制器回显 `orderId` 的**值**（不只是 key 集合），确认业务读到完整体。

### 2.2 语义通道（`@AuditLog`）：装配点是本任务最难的部分

边界通道只能保证"有记录"，语义通道才决定"记录里有没有业务含义"。
Reactive 侧为此连续踩了四个坑，每一次都是靠**实测证据**而不是推理定位的：

| 尝试 | 做法 | 实测结果 | 结论 |
|---|---|---|---|
| 1 | 读 `exchange` 的 `BEST_MATCHING_HANDLER` 属性 | `operation=null` | 属性被写在 `mutate()` 出来的另一个 exchange 上 |
| 2 | 用 `mutate().attributes(...)` 共享属性表 | **编译失败** | Spring 6.2 的 `ServerWebExchange.Builder` **没有** `attributes(...)` 方法 |
| 3 | 自定义 `ServerWebExchangeDecorator` 覆盖 `getAttributes()`/`mutate()` | 仍未生效 | `ServerWebExchangeDecorator.mutate()` 基于原始 delegate 构造，属性表无法贯穿 |
| 4 | **包装 `HandlerAdapter`**（`BeanPostProcessor`） | ✅ 生效 | `HandlerMethod` 与真正的 exchange 都能拿到 |

**还有一个"先到者胜"的真实故障**：过滤器和适配器两处都做了语义填充，
而过滤器那次拿不到请求视图 → `resourceId` 恒为 `null`。
用记录里的标签定位到 `diagReqNull=true` 才确认。
**教训**：同一职责只保留一处实现；两处都做会变成"谁先到谁说了算"。

另外两个必须记录的实现细节：

1. **注册表不能是空实现**：`AuditCaptureService` 是**通过注册表**取元数据的。
   Reactive 侧最初注册 `AuditMethodRegistry.empty()`，导致注解永远解析不出来
   （即使适配器包装、注解解析本身都正确）。改为 `ResolvingAuditMethodRegistry`
   （请求期解析 + 缓存）后才通。
2. **`resourceIdParam` 的解析时机**：反应式下没有可靠的"方法执行结束"钩子
   （挂在 HandlerAdapter 返回的 Mono 上的回调实测不触发），
   因此把"只依赖请求"的那部分解析提前到 `applySemantic`；
   依赖方法实参的表达式形式仍留在 `finishSemantic`。两种环境共用同一段解析逻辑。

### 2.3 验证结果汇总

| 验证项 | Servlet | Reactive |
|---|---|---|
| 单元测试 | 72 + 33 = **105/105 通过** | 同上（共用） |
| 端到端验收 | **11/11 通过** | — |
| 真机采集 | Tomcat 8083，**11/11** | Netty，**6/6 条记录** |
| 语义通道（真机） | 已有用例 | **7/7 通过**（operation/actionType/resourceType/resourceId/tags） |
| 环境自检 | — | `REACTIVE 且过滤器已注册` |
| 状态码真实性 | 已有用例 | error→500、404→404 |
| 脱敏 | 已有用例 | Authorization 未明文落库 |
| 哈希链 | 已有用例 | 8/8 带链哈希，seq 无重复 |
| 请求体完整性 | 已有用例 | 业务回显 `orderId=WF-1`（tee 未吃掉字节） |

### 2.4 另外两条环境相关的重要教训

**（1）旧 jar 会让"源码已改"变成假象。**
服务器上首次运行 WebFlux 仍报 `NoClassDefFoundError`，根因与代码无关：
classpath 解析到 `~/.m2` 里**改造前的旧 starter jar**。
多模块工程改了模块内类型后必须 `mvn install` 刷新本地仓库。
已用 `javap` 确认服务器仓库里是新的 `interface` 版本后才重跑。

**（2）Maven 不允许同一依赖声明两次，且"后者覆盖前者"。**
我曾为测试重复声明 `spring-webflux` / `reactor-core`（原本已有 `optional=true`），
Maven 报 `must be unique` 并把它们降级为 test 作用域 ——
结果是**主源码编译时反而看不到这两个依赖**，在无外网的构建机上直接构建失败。
`optional` 依赖对本模块自身的编译与测试本来就是可用的，重复声明有害无益。

