# 离线环境下运行单元测试
#
# 背景：本地 Maven 仓库缺少 maven-surefire-plugin 的 provider 依赖（surefire-junit-platform 等
# 只有空目录没有 jar），且无外网，因此 `mvn test` 无法执行。
# 但 JUnit Platform 的 launcher/engine 齐备，因此本脚本：
#   1) 用 mvn test-compile 编译测试源码；
#   2) 从本地仓库挑选 JUnit 相关 jar 组装测试 classpath；
#   3) 编译并运行 JUnitConsoleRunner，直接以 JUnit Platform 执行测试。
#
# 注意：本文件含中文注释，必须以 UTF-8 BOM 保存，否则 Windows PowerShell 5.1 会按 ANSI 解析而报语法错误。

$ErrorActionPreference = 'Stop'
$repo = 'E:/.m2/repository'
$projectRoot = 'F:\audit-log'
$junitVersion = '5.12.2'
$platformVersion = '1.12.2'

function Resolve-Jar([string]$relative, [string]$version) {
    $dir = Join-Path $repo (Join-Path $relative $version)
    if (-not (Test-Path $dir)) { throw "缺少构件: $relative : $version" }
    $jar = Get-ChildItem -LiteralPath $dir -Filter *.jar |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } |
        Select-Object -First 1
    if ($null -eq $jar) { throw "目录下无 jar: $dir" }
    return $jar.FullName
}

$junitJars = @(
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-api'     $junitVersion),
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-engine'  $junitVersion),
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-params'  $junitVersion),
    (Resolve-Jar 'org/junit/platform/junit-platform-commons' $platformVersion),
    (Resolve-Jar 'org/junit/platform/junit-platform-engine'  $platformVersion),
    (Resolve-Jar 'org/junit/platform/junit-platform-launcher' $platformVersion),
    (Resolve-Jar 'org/opentest4j/opentest4j'               '1.3.0'),
    (Resolve-Jar 'org/apiguardian/apiguardian-api'          '1.1.2')
)

# 编译离线运行器
$runnerSrc = Join-Path $projectRoot 'tools/junit-runner/src'
$runnerOut = Join-Path $projectRoot 'tools/junit-runner/classes'
if (Test-Path $runnerOut) { Remove-Item -Recurse -Force $runnerOut }
New-Item -ItemType Directory -Force -Path $runnerOut | Out-Null
& javac -encoding UTF-8 -d $runnerOut -cp ($junitJars -join ';') (Join-Path $runnerSrc 'io/github/pkyit/auditlog/tools/JUnitConsoleRunner.java')
if ($LASTEXITCODE -ne 0) { throw '编译 JUnitConsoleRunner 失败' }

# 各模块：依赖 classpath 来源
$modules = @(
    @{
        Name = 'audit-log-core'
        RuntimeCpFile = $null
        ExtraJars = @()
    },
    @{
        Name = 'audit-log-spring-boot-starter'
        RuntimeCpFile = 'audit-log-samples/target/cp.txt'
        ExtraJars = @()
    }
)

$overallExit = 0
foreach ($module in $modules) {
    $moduleName = $module.Name
    $testClasses = Join-Path $projectRoot "$moduleName/target/test-classes"
    $mainClasses = Join-Path $projectRoot "$moduleName/target/classes"
    if (-not (Test-Path $testClasses)) {
        Write-Host "[$moduleName] 没有编译后的测试类，跳过（先执行 mvn test-compile）"
        continue
    }

    $parts = New-Object System.Collections.Generic.List[string]
    $parts.Add($testClasses)
    $parts.Add($mainClasses)
    $parts.Add($runnerOut)
    foreach ($jar in $junitJars) { $parts.Add($jar) }

    if ($module.RuntimeCpFile) {
        $cpFile = Join-Path $projectRoot $module.RuntimeCpFile
        # cp.txt 属于构建产物：`mvn clean` 或清理 target 后它会消失。
        # 这里自动重建，避免"清理后测试静默发现 0 个用例"这种假通过。
        if (-not (Test-Path $cpFile)) {
            Write-Host "[$moduleName] 运行期 classpath 缺失，自动重建：$($module.RuntimeCpFile)"
            & powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $projectRoot 'tools/build-classpath.ps1') | Out-Null
        }
        if (Test-Path $cpFile) {
            $runtimeCp = (Get-Content -Raw $cpFile).Trim()
            foreach ($entry in ($runtimeCp -split ';')) {
                if ($entry.Trim().Length -gt 0) { $parts.Add($entry.Trim()) }
            }
        }
        else {
            Write-Host "[$moduleName] 警告：无法获得运行期 classpath，$moduleName 的测试可能无法被发现"
        }
    }
    # core 模块测试需要 jackson / slf4j
    if ($moduleName -eq 'audit-log-core') {
        foreach ($relative in @(
            'com/fasterxml/jackson/core/jackson-databind|2.19.4',
            'com/fasterxml/jackson/core/jackson-core|2.19.4',
            'com/fasterxml/jackson/core/jackson-annotations|2.19.4',
            'com/fasterxml/jackson/datatype/jackson-datatype-jsr310|2.19.4',
            'org/slf4j/slf4j-api|2.0.17')) {
            $pair = $relative -split '\|'
            $parts.Add((Resolve-Jar $pair[0] $pair[1]))
        }
    }

    # starter 模块的测试需要 spring-test（MockHttpServletRequest）等测试期依赖，
    # 这些不在运行期 classpath（cp.txt）中，必须显式补充，否则测试类无法被发现。
    if ($moduleName -eq 'audit-log-spring-boot-starter') {
        foreach ($relative in @(
            'org/springframework/spring-test|6.2.15',
            'org/springframework/boot/spring-boot-test|3.5.9',
            'jakarta/servlet/jakarta.servlet-api|6.0.0')) {
            $pair = $relative -split '\|'
            $parts.Add((Resolve-Jar $pair[0] $pair[1]))
        }
    }

    $classpath = (($parts | Select-Object -Unique) -join ';')
    Write-Host ""
    Write-Host "==================== $moduleName ===================="
    & java '-Dfile.encoding=UTF-8' -cp $classpath io.github.pkyit.auditlog.tools.JUnitConsoleRunner $testClasses
    if ($LASTEXITCODE -ne 0) { $overallExit = 1 }
}

Write-Host ""
if ($overallExit -eq 0) {
    Write-Host "全部单元测试通过。"
    # 显式 exit：避免脚本末尾残留的 $LASTEXITCODE（来自被中断或非零退出的子进程）
    # 影响调用方看到的退出码，从而让 CI 误判为失败。
    exit 0
} else {
    Write-Host "存在失败的单元测试，请查看上方报告。"
    exit 1
}
