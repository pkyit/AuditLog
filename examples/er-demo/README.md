# er-demo —— audit-log 框架的外部消费验证工程

一个最小的 ER（实体关系）业务系统，用于在**真实 PostgreSQL** 上验证
`io.github.pkyit:audit-log-spring-boot-starter` 的可用性。

## 为什么单独成工程

本工程**不继承**框架的父 POM，也**不加入**其 `<modules>`。
这样 `audit-log-spring-boot-starter` 只能从 Maven 中央仓库解析，
与真实使用者的处境一致；若放在同一 reactor 中，依赖会从本地模块解析，
就测不出"已发布制品是否真的可用"。

## 领域模型

```
er_customer ──< er_order ──< er_order_item >── er_product
```

客户与订单一对多，订单与商品通过明细多对多，共两级外键关系。

## 接口

| 方法 | 路径 | 审计注解 |
|---|---|---|
| POST | `/api/customers` | `@AuditLog(创建客户)` |
| GET | `/api/customers/{id}` | 无（仅边界通道） |
| GET | `/api/customers` | 无（仅边界通道） |
| PUT | `/api/customers/{id}` | `@AuditLog(更新客户)` |
| DELETE | `/api/customers/{id}` | `@AuditLog(删除客户)` |
| POST | `/api/products` | `@AuditLog(创建商品)` |
| GET | `/api/products` | 无（仅边界通道） |
| POST | `/api/orders` | `@AuditLog(创建订单)`，跨表事务 |
| GET | `/api/orders/{id}` | 无（仅边界通道） |
| DELETE | `/api/orders/{id}` | `@AuditLog(删除订单)` |
| GET | `/api/reports/customer-summary` | 无（仅边界通道） |
| GET | `/api/ops/counts` | 无（仅边界通道） |

## 运行

```bash
# 建库（需 PostgreSQL）
mvn -B clean package -DskipTests
java -jar target/er-demo-1.0.0.jar
```

数据库连接见 `src/main/resources/application.yml`，可用命令行覆盖：

```bash
java -jar target/er-demo-1.0.0.jar \
  --spring.datasource.url=jdbc:postgresql://host:5432/er_demo \
  --spring.datasource.username=... --spring.datasource.password=...
```

## 验证

```bash
python verify-er.py
```

`verify-er.py` 的关键设计：**审计结论不由应用自报**。
应用只负责处理业务请求，审计记录一律通过独立的 JDBC 连接
（`tools/db/SqlRunner.java`）直接读库核对，
避免把"应用说自己记了"当成"数据库里真有"。

脚本会先清空业务数据，再依次验证业务功能、事务提交与回滚、
并发写入，以及审计的边界/语义通道、结果判定、脱敏与哈希链。

## 已知前置条件

* **编译期必须开启 `-parameters`**（本项目已配置）。
  缺少该标志时 Spring MVC 无法解析 `@PathVariable` 参数名，
  运行期返回 400 且错误信息不易理解；审计框架的参数名解析同样依赖它。
* 审计表需预先存在（`--audit.log.store.auto-create-table=false` 时的默认行为），
  建表脚本见框架的 `docs/15-ER系统实测问题与处理方案.md`。

## 与框架缺陷的关系

本工程是 `docs/15-ER系统实测问题与处理方案.md` 中 F1/F2/F3/F4 四项发现的
复现载体。其中 F1（DDL 使用 `CLOB` 导致 PostgreSQL 启动失败）与
F2（默认 order 下语义通道静默失效）在本工程上稳定复现。
