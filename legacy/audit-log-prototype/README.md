# AuditLog - 高性能 Spring Boot 日志审计框架

## 简介

AuditLog 是一个高性能的 Spring Boot 日志审计框架，基于 Spring MVC
的扩展机制和事件驱动模型实现。该框架能够在不修改任何业务代码的前提下，自动完成请求入口、响应结果、调用耗时、客户端信息等关键数据的采集。经压力测试验证，在
1000 并发连接下仍能保持约 18,000 QPS 的处理能力。

源码地址：[https://gitee.com/pengkaiyan/audit-log](https://gitee.com/pengkaiyan/audit-log)

## 特性

- **零侵入性**：通过注解方式启用，无需修改现有业务代码
- **灵活配置**：支持全局配置和方法级细粒度配置
- **敏感字段保护**：可配置敏感字段进行脱敏处理
- **高性能**：基于 Spring MVC 扩展机制而非 AOP，性能更优
- **异步处理**：采用事件驱动模型，不影响主业务流程性能
- **精确控制**：只对明确标记的方法进行审计，避免全量方法拦截

## 使用方法

### 1. 启用审计日志功能

在 Spring Boot 启动类上添加 `@EnableAuditLog` 注解：

```java

@SpringBootApplication
@EnableAuditLog(
    basePackages = {"com.example.controller"},
    enable = "${audit.log.enable:true}",
    logHeaders = "${audit.log.headers:true}",
    logRequest = "${audit.log.request:true}",
    logResponse = "${audit.log.response:true}",
    sensitiveHeaders = {"Authorization", "Cookie"}
)
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

### 2. 方法级细粒度控制

对于需要特殊配置的接口，可以使用 `@AuditLog` 注解：

```java
@RestController
@RequestMapping("/api")
public class UserController {

    @AuditLog(
        name = "查询用户列表",
        logHeaders = true,
        logRequestParams = true,
        logResponseParams = true
    )
    @GetMapping("/users")
    public ResponseEntity<List<User>> getUsers() {
        // 业务逻辑
        return ResponseEntity.ok(userService.findAll());
    }
}
```

### 3. 配置文件设置

在 `application.yml` 中配置相关参数：

```yaml
server:
  port: 8083

logging:
  level:
    com.github.pkyit.module.audit: DEBUG
```

## 核心组件

### 注解

- `@EnableAuditLog`：全局启用审计日志功能
- `@Log`：方法级审计日志配置

### 配置类

- `AuditLogConfiguration`：核心配置类
- `AuditLogConfig`：全局配置信息存储
- `MethodLog`：方法级配置信息存储

### Web 组件

- `AuditLogHandlerAdapter`：自定义请求处理器适配器
- `AuditLogHandlerMethod`：核心执行逻辑
- `AuditLogWebMvcRegistrations`：注册自定义 HandlerAdapter

### 事件模型

- `AuditLogRequestEvent`：日志事件封装
- `AuditLogListener`：日志事件监听器

## 工作原理

1. 通过 `@EnableAuditLog` 注解导入 `AuditLogConfiguration` 配置类
2. 配置类扫描指定包下的 Controller，并解析方法上的 `@Log` 注解
3. 注册自定义的 `AuditLogHandlerAdapter` 替换默认的处理器适配器
4. 在 `AuditLogHandlerMethod` 中重写 `doInvoke` 方法，在方法调用前后记录日志
5. 通过事件驱动模型异步处理日志数据

## 测试验证

### 单元测试

项目包含以下测试类用于验证功能：

1. `AuditLogTest` - 验证配置是否正确加载
2. `AuditLogIntegrationTest` - 集成测试，验证日志记录功能

### 手动测试

启动应用程序后，可以通过以下端点测试日志审计功能：

1. `GET /api/users` - 获取所有用户（使用全局配置）
2. `GET /api/users/{id}` - 根据ID获取用户（使用方法级配置）
3. `POST /api/users` - 创建用户（记录请求和响应）
4. `PUT /api/users/{id}` - 更新用户（禁用日志记录）
5. `DELETE /api/users/{id}` - 删除用户（使用全局配置）

### 验证日志输出

当日志级别设置为 DEBUG 时，应该能看到类似以下的审计日志：

```
DEBUG ApiAuditLog - 请求详细信息: AuditLog{clientIp='...', controllerName='...', methodName='...', ...}
```

## 注意事项

- 该组件不使用 AOP 技术，而是基于 Spring MVC 的底层扩展机制实现
- 日志记录仅在 DEBUG 级别下进行，以减少性能影响
- 敏感字段会自动过滤，不会记录到日志中