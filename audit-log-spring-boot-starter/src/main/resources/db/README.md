# 审计表建表脚本

生产环境通常不授予应用 DDL 权限，此时应保持
`audit.log.store.auto-create-table=false`（默认值），
用下面对应数据库的脚本建立审计表：

| 数据库 | 脚本 | 是否已实测 |
|---|---|---|
| PostgreSQL | `audit-log-postgresql.sql` | 是（PostgreSQL 14.20） |
| MySQL / MariaDB | `audit-log-mysql.sql` | 否 |
| Oracle | `audit-log-oracle.sql` | 否 |

未实测的脚本是为避免使用者从源码反推表结构而提供，使用前请自行核对。
若表名不是默认的 `audit_log`，请在脚本中整体替换，
并同步设置 `audit.log.store.table-name`。

## 大文本列的类型差异

SQL 没有统一的大文本类型名，这是本框架自动建表曾经出错的地方：

* PostgreSQL、MySQL、MariaDB、H2、SQLite、SQL Server 使用 `TEXT`
* Oracle、DB2 只认 `CLOB`

框架在自动建表时会读取数据库产品名来决定使用哪一种，
并在启动日志中打印实际选用的类型。手工执行脚本时请选用与目标数据库匹配的那一份。
