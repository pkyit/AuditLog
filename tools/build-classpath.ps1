# 组装示例应用的运行时 classpath 并启动验收（离线环境专用）
#
# 背景：本环境未缓存 spring-boot-maven-plugin 与 maven-dependency-plugin 的传递依赖，
# 因此 `mvn spring-boot:run` 与 `dependency:build-classpath` 都不可用。
# 本脚本直接从本地仓库挑选所需 jar 组装 classpath，效果等价。

$ErrorActionPreference = 'Stop'
$repo = 'E:/.m2/repository'
$projectRoot = 'F:\audit-log'

function Pick-Jar([string]$relative, [string]$version) {
    $dir = Join-Path $repo (Join-Path $relative $version)
    if (-not (Test-Path $dir)) { throw "missing artifact: $relative : $version" }
    $jar = Get-ChildItem -LiteralPath $dir -Filter *.jar |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if ($null -eq $jar) { throw "no jar in $dir" }
    return $jar.FullName
}

# 每行格式: 相对路径|版本
$spec = @'
org/springframework/boot/spring-boot|3.5.9
org/springframework/boot/spring-boot-autoconfigure|3.5.9
org/springframework/boot/spring-boot-actuator|3.5.9
org/springframework/boot/spring-boot-actuator-autoconfigure|3.5.9
org/springframework/boot/spring-boot-starter|3.5.9
org/springframework/boot/spring-boot-starter-web|3.5.9
org/springframework/boot/spring-boot-starter-jdbc|3.5.9
org/springframework/boot/spring-boot-starter-actuator|3.5.9
org/springframework/boot/spring-boot-starter-json|3.5.9
org/springframework/boot/spring-boot-starter-logging|3.5.9
org/springframework/boot/spring-boot-starter-tomcat|3.5.9
org/springframework/spring-core|6.2.15
org/springframework/spring-beans|6.2.15
org/springframework/spring-context|6.2.15
org/springframework/spring-aop|6.2.15
org/springframework/spring-expression|6.2.15
org/springframework/spring-web|6.2.15
org/springframework/spring-webmvc|6.2.15
org/springframework/spring-jdbc|6.2.15
org/springframework/spring-tx|6.2.15
org/springframework/spring-jcl|6.2.15
com/fasterxml/jackson/core/jackson-databind|2.19.4
com/fasterxml/jackson/core/jackson-core|2.19.4
com/fasterxml/jackson/core/jackson-annotations|2.19.4
com/fasterxml/jackson/datatype/jackson-datatype-jsr310|2.19.4
ch/qos/logback/logback-classic|1.5.21
ch/qos/logback/logback-core|1.5.21
org/slf4j/slf4j-api|2.0.17
org/slf4j/jul-to-slf4j|2.0.17
org/apache/logging/log4j/log4j-to-slf4j|2.24.3
org/apache/logging/log4j/log4j-api|2.24.3
org/yaml/snakeyaml|2.4
com/h2database/h2|2.1.214
io/micrometer/micrometer-core|1.15.6
io/micrometer/micrometer-observation|1.15.6
io/micrometer/micrometer-commons|1.15.6
org/apache/tomcat/embed/tomcat-embed-core|10.1.49
org/apache/tomcat/embed/tomcat-embed-el|10.1.49
org/apache/tomcat/embed/tomcat-embed-websocket|10.1.49
jakarta/annotation/jakarta.annotation-api|2.1.1
jakarta/servlet/jakarta.servlet-api|6.0.0
jakarta/validation/jakarta.validation-api|3.0.2
com/zaxxer/HikariCP|6.3.3
'@

$entries = New-Object System.Collections.Generic.List[string]
$entries.Add("$projectRoot\audit-log-samples\target\classes")
$entries.Add("$projectRoot\audit-log-spring-boot-starter\target\classes")
$entries.Add("$projectRoot\audit-log-core\target\classes")

$missing = New-Object System.Collections.Generic.List[string]
# 注意：here-string 末尾会带回车换行，必须先 Trim，否则最后一个 classpath 条目
# 会带上换行符，导致 JVM 无法加载该 jar（表现为莫名其妙的 ClassNotFoundException）。
foreach ($line in ($spec -split "`r?`n")) {
    $trimmed = $line.Trim()
    if ($trimmed.Length -eq 0) { continue }
    $parts = $trimmed -split '\|'
    if ($parts.Count -ne 2) { continue }
    try {
        $entries.Add((Pick-Jar $parts[0].Trim() $parts[1].Trim()))
    }
    catch {
        $missing.Add($trimmed)
    }
}

if ($missing.Count -gt 0) {
    Write-Host "[warn] 以下 artifact 未找到，已跳过："
    $missing | ForEach-Object { Write-Host "   $_" }
}

$unique = $entries | Select-Object -Unique
$classpath = ($unique -join ';')
$outFile = "$projectRoot\audit-log-samples\target\cp.txt"
Set-Content -LiteralPath $outFile -Value $classpath -Encoding ASCII
Write-Host "classpath entries = $($unique.Count)"
Write-Host "written to $outFile"
