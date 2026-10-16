# 针对单个测试方法反复运行，用于排查稳定性（flaky）问题
# 用法: powershell -File tools/probe-one-test.ps1 <全限定类名#方法名> [次数]

$ErrorActionPreference = 'Stop'
$repo = 'E:/.m2/repository'
$projectRoot = 'F:\audit-log'
$target = if ($args.Count -ge 1) { $args[0] } else { 'io.github.pkyit.auditlog.core.pipeline.AuditPipelineTest#doesNotLoseDataWhenStoreFails' }
$runs = if ($args.Count -ge 2) { [int]$args[1] } else { 8 }

function Resolve-Jar([string]$relative, [string]$version) {
    $jar = Get-ChildItem -LiteralPath (Join-Path $repo (Join-Path $relative $version)) -Filter *.jar |
        Where-Object { $_.Name -notmatch 'sources|javadoc' } | Select-Object -First 1
    return $jar.FullName
}

$cp = (@(
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-api' '5.12.2'),
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-engine' '5.12.2'),
    (Resolve-Jar 'org/junit/jupiter/junit-jupiter-params' '5.12.2'),
    (Resolve-Jar 'org/junit/platform/junit-platform-commons' '1.12.2'),
    (Resolve-Jar 'org/junit/platform/junit-platform-engine' '1.12.2'),
    (Resolve-Jar 'org/junit/platform/junit-platform-launcher' '1.12.2'),
    (Resolve-Jar 'org/opentest4j/opentest4j' '1.3.0'),
    (Resolve-Jar 'org/apiguardian/apiguardian-api' '1.1.2'),
    (Join-Path $projectRoot 'audit-log-core/target/classes'),
    (Join-Path $projectRoot 'audit-log-core/target/test-classes'),
    (Join-Path $projectRoot 'tools/junit-runner/classes'),
    (Resolve-Jar 'com/fasterxml/jackson/core/jackson-databind' '2.19.4'),
    (Resolve-Jar 'com/fasterxml/jackson/core/jackson-core' '2.19.4'),
    (Resolve-Jar 'com/fasterxml/jackson/core/jackson-annotations' '2.19.4'),
    (Resolve-Jar 'com/fasterxml/jackson/datatype/jackson-datatype-jsr310' '2.19.4'),
    (Resolve-Jar 'org/slf4j/slf4j-api' '2.0.17')
) -join ';')

for ($i = 1; $i -le $runs; $i++) {
    $output = & java '-Dfile.encoding=UTF-8' -cp $cp io.github.pkyit.auditlog.tools.JUnitConsoleRunner `
        (Join-Path $projectRoot 'audit-log-core/target/test-classes') $target 2>&1
    $summary = ($output | Select-String -Pattern '发现用例' | Select-Object -First 1).Line
    $failure = ($output | Select-String -Pattern 'AssertionFailedError' | Select-Object -First 1).Line
    Write-Host ("run {0}: {1}" -f $i, $summary)
    if ($failure) { Write-Host ("        {0}" -f $failure.Trim()) }
}
