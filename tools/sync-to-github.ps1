# 把本仓库当前提交同步到 GitHub 上的 pkyit/AuditLog。
#
# 背景：本仓库的历史与 GitHub 仓库不同源（GitHub 上保留了最初的 init 提交，
# 之后以一次提交替换为框架实现），因此不能用 git remote push 直接同步，
# 而是把已提交内容导出后覆盖到 GitHub 克隆的工作区再提交。
#
# 之所以这样做而不是强制推送：强制推送会丢弃 GitHub 上的原有提交，
# 而保留历史更便于追溯"这份代码从哪来"。
#
# 本文件含中文注释，必须以 UTF-8 BOM 保存，否则 Windows PowerShell 5.1 会按 ANSI 解析。

param(
    [string]$WorkDir = 'F:\AuditLog',
    [string]$Remote = 'git@github.com:pkyit/AuditLog.git',
    [string]$Branch = 'master',
    [string]$Message = '',
    [switch]$Push
)

$ErrorActionPreference = 'Stop'
$source = Split-Path -Parent $PSScriptRoot
$zip = Join-Path $env:TEMP 'auditlog-sync.zip'

# 本脚本同步的是**已提交**内容（git archive HEAD），未提交的改动不会被同步。
# 若不提示，会出现"改了却同步不过去"的静默偏差，因此这里显式警告。
$dirty = & git -C $source status --porcelain
if ($dirty) {
    Write-Warning "源仓库存在 $((($dirty | Measure-Object).Count)) 项未提交改动，这些改动不会被同步："
    $dirty | Select-Object -First 10 | ForEach-Object { Write-Warning "  $_" }
}

if (-not (Test-Path $WorkDir)) {
    Write-Host "克隆 $Remote 到 $WorkDir"
    & git clone --quiet $Remote $WorkDir
    if ($LASTEXITCODE -ne 0) { throw '克隆失败' }
}

# 从源仓库已提交内容导出：用 zip 中转而不是管道给 tar
# （PowerShell 管道会破坏 tar 的二进制流，实测出现 bad header checksum）
if (Test-Path $zip) { Remove-Item $zip -Force }
& git -C $source archive --format=zip -o $zip HEAD
if ($LASTEXITCODE -ne 0) { throw '导出失败' }

# 恢复工作区后清空（保留 .git，历史不动）
& git -C $WorkDir checkout -- .
& git -C $WorkDir clean -fdq
Get-ChildItem -Force $WorkDir | Where-Object { $_.Name -ne '.git' } | Remove-Item -Recurse -Force
Expand-Archive -Path $zip -DestinationPath $WorkDir -Force

& git -C $WorkDir add -A
$changed = (& git -C $WorkDir status --porcelain | Measure-Object).Count
if ($changed -eq 0) {
    Write-Host '无差异，无需同步'
    exit 0
}
Write-Host "待提交变更: $changed 项"

if (-not $Message) {
    $Message = "sync: 同步自开发仓库 $((& git -C $source rev-parse --short HEAD))"
}
& git -C $WorkDir -c user.name='pkyit' -c user.email='pkyit@qq.com' commit -q -m $Message
& git -C $WorkDir log --oneline -1

if ($Push) {
    & git -C $WorkDir push origin $Branch
    if ($LASTEXITCODE -ne 0) { throw '推送失败' }
    Write-Host "已推送到 $Remote ($Branch)"
}
else {
    Write-Host '未指定 -Push，仅本地提交。确认无误后加 -Push 推送。'
}
