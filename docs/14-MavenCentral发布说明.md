# 14 - Maven Central 发布说明

**坐标**：`io.github.pkyit`
**已发布版本**：`1.0.0`
**发布方式**：Central Portal（`central-publishing-maven-plugin` 0.12.0）

---

## 一、使用者如何引入

仅需 `audit-log-spring-boot-starter` 一个依赖，`audit-log-core` 会被自动带入：

```xml
<dependency>
    <groupId>io.github.pkyit</groupId>
    <artifactId>audit-log-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

已发布的构件：

| 构件 | 说明 |
|---|---|
| `io.github.pkyit:audit-log-parent:1.0.0` | 父 POM（`pom` 打包） |
| `io.github.pkyit:audit-log-core:1.0.0` | 核心引擎，无 Web 依赖 |
| `io.github.pkyit:audit-log-spring-boot-starter:1.0.0` | Spring Boot 自动配置（Servlet 与 Reactive 自适应） |

每个 `jar` 均附带 `-sources.jar`、`-javadoc.jar` 与 `.asc` 签名。
两个示例模块**不发布**（Central 面向可复用库，示例属于本仓库的验证载体）。

---

## 二、发布流程

前置条件（本机一次性配置）：

1. **GnuPG 密钥**：私钥位于本机密钥环，公钥已上传至
   `keyserver.ubuntu.com` / `keys.openpgp.org` / `pgp.mit.edu`。
   密钥为 RSA 3072、仅签名+认证能力、**不含签名子钥**
   （Central 只能对主键验签，存在签名子钥会导致验签失败）。
2. **Portal 凭据**：写入 `~/.m2/settings.xml` 的 `<server><id>central</id>`，
   值为 Portal 的 User Token。凭据不进入版本库。
3. **GnuPG 可执行文件**：`maven-gpg-plugin` 无法自行推断路径，
   若 `gpg` 不在 `PATH`，需通过 `-Dgpg.executable` 指定。

发布命令：

```powershell
# 上传并由 Central 校验，之后在 Portal 上人工确认发布（默认行为）
powershell -NoProfile -ExecutionPolicy Bypass -File tools/release.ps1

# 上传后自动发布
powershell -NoProfile -ExecutionPolicy Bypass -File tools/release.ps1 -AutoPublish
```

`central.autoPublish` 默认为 `false`。**构件一经发布不可修改或删除**，
因此默认保留人工确认环节；确认制品无误后再用 `-AutoPublish`。

---

## 三、发布时的注意事项

1. **版本不可复用**。`1.0.0` 已占用，后续任何改动都必须升版本号，
   不能覆盖已发布的内容。
2. **新发布的构件在缓存代理上可能暂时 404**。
   例如 aliyun 公共仓库在发布后一段时间内查不到该坐标为 404，
   这是缓存代理尚未回源所致，`repo1.maven.org` 上已可正常拉取。
3. **`-D` 参数在 Windows PowerShell 下需要加引号**。
   `-Dcentral.autoPublish=true` 会被 PowerShell 拆成两个参数，
   必须写成 `'-Dcentral.autoPublish=true'`。
4. **`autoPublish` 没有内置的 Maven 属性**，本仓库在父 POM 中把它暴露为
   `${central.autoPublish}`，否则命令行无法覆盖。
5. **签名私钥的备份**：私钥一旦丢失，将无法再用同一密钥发布后续版本，
   而 Central 上的历史签名无法重新生成。撤销证书位于
   `%APPDATA%\gnupg\openpgp-revocs.d\`，应与私钥一并离线备份。

---

## 四、本次发布的验证证据

**（1）构件可公网拉取** —— `repo1.maven.org` 上全部文件 HTTP 200：

```
audit-log-parent-1.0.0.pom                     17689
audit-log-parent-1.0.0.pom.asc                   713
audit-log-core-1.0.0.jar                      119277
audit-log-core-1.0.0-sources.jar               99102
audit-log-core-1.0.0-javadoc.jar              454783
audit-log-core-1.0.0.jar.asc                     713
audit-log-spring-boot-starter-1.0.0.jar       161929
audit-log-spring-boot-starter-1.0.0-javadoc.jar 531166
audit-log-spring-boot-starter-1.0.0.jar.sha1      40
```

**（2）第三方可独立解析** —— 用一个与本源码无关的消费工程，
配合**空本地仓库**、**直连 `repo1.maven.org`**（排除 aliyun 镜像影响）执行：

```
Downloaded io/github/pkyit/audit-log-spring-boot-starter/1.0.0/...pom
Downloaded io/github/pkyit/audit-log-parent/1.0.0/...pom          <- 父 POM 也能解析
Downloaded io/github/pkyit/audit-log-core/1.0.0/...pom
Downloaded io/github/pkyit/audit-log-spring-boot-starter-1.0.0.jar
Downloaded io/github/pkyit/audit-log-core-1.0.0.jar
[INFO] Compiling 1 source file with javac [debug release 17]
[INFO] BUILD SUCCESS
```

即依赖图完整、可传递解析，且引用 `AuditStore` / `AuditRecord` / `@AuditLog` 的源码可编译通过。

**（3）签名对消费者有效** —— 用独立的 GnuPG 主目录从公钥服务器取回公钥后验签：

```
gpg: using RSA key 706246AB452B02E8E8956A9539AE765EF5C0DBEF
gpg: Good signature from "pengkaiyan <pkyit@qq.com>"
```

（`not certified with a trusted signature` 属正常提示，表示该密钥不在校验者的信任网内。）

**（4）发布前回归** —— 单元测试 105/105、Servlet 端到端 11/11、
WebFlux 边界+语义 25 项断言，均在发布前的同一份代码上通过。

---

## 五、如需撤回

Central 上的构件**不能删除**。若某个版本存在问题，只能发布新版本修正，
并在 README 或发布说明中标注问题版本。
