# 打包工作区源码为 tar.gz（用于上传到测试服务器）
# 排除构建产物、版本库、日志与本地临时文件；包含未提交的工作区改动。
$ErrorActionPreference = 'Stop'
$root = 'F:\audit-log'
$out = Join-Path $root 'target\audit-log-src.tar.gz'
New-Item -ItemType Directory -Force -Path (Split-Path $out) | Out-Null
if (Test-Path $out) { Remove-Item $out -Force }

# 用数组传参，避免 PowerShell 5.1 对原生命令参数与反引号续行的解析问题
$tarArgs = @(
    '-czf', $out,
    '--exclude=./target',
    '--exclude=./.git',
    '--exclude=./audit-log-core/target',
    '--exclude=./audit-log-spring-boot-starter/target',
    '--exclude=./audit-log-samples/target',
    '--exclude=./tools/junit-runner/classes',
    '--exclude=./logs',
    '-C', $root,
    '.'
)
& tar @tarArgs
if ($LASTEXITCODE -ne 0) { throw "tar 打包失败，退出码 $LASTEXITCODE" }
$size = (Get-Item $out).Length
Write-Host ("打包完成: {0} ({1:N1} KB)" -f $out, ($size / 1KB))
