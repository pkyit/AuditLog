# 发布 audit-log 到 Maven Central（Central Portal）
#
# 用法：
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/release.ps1
#   powershell -NoProfile -ExecutionPolicy Bypass -File tools/release.ps1 -AutoPublish
#
# 说明：
#   * 默认 autoPublish=false：上传后由 Central 校验，需在
#     https://central.sonatype.com/publishing/deployments 手动确认发布。
#     构件一经发布不可修改或删除，因此默认保留人工确认环节。
#   * -AutoPublish 会连同发布一并完成，适用于已确认制品无误的场景。
#   * 签名密钥与 Portal 凭据分别位于本机 GnuPG 密钥环与 ~/.m2/settings.xml，
#     均不进入版本库。
#
# 本文件含中文注释，必须以 UTF-8 BOM 保存，否则 Windows PowerShell 5.1 会按 ANSI 解析。

param(
    [switch]$AutoPublish,
    [switch]$SkipTests
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $PSScriptRoot

# 定位 gpg：优先 PATH，其次 GnuPG 默认安装位置。
# maven-gpg-plugin 无法自行推断路径，未安装到 PATH 时必须显式传入。
$gpg = (Get-Command gpg -ErrorAction SilentlyContinue).Source
if (-not $gpg) {
    $candidates = @(
        'C:\Program Files\GnuPG\bin\gpg.exe',
        'C:\Program Files (x86)\GnuPG\bin\gpg.exe',
        'C:\Program Files\Gpg4win\bin\gpg.exe',
        'C:\Program Files (x86)\Gpg4win\bin\gpg.exe'
    )
    $gpg = $candidates | Where-Object { Test-Path $_ } | Select-Object -First 1
}
if (-not $gpg) {
    throw '未找到 gpg。请先安装 GnuPG（winget install GnuPG.GnuPG）后重试。'
}
Write-Host "使用 gpg: $gpg"

# 注意：不要用 $args 承接参数，它是 PowerShell 自动变量，赋值行为不可靠。
$mvnArgs = @('-B', '-Prelease', "-Dgpg.executable=$gpg")
if ($SkipTests) { $mvnArgs += '-DskipTests' }
if ($AutoPublish) { $mvnArgs += '-Dcentral.autoPublish=true' }
$mvnArgs += 'deploy'

Write-Host "执行: mvn $($mvnArgs -join ' ')"
Push-Location $projectRoot
try {
    & mvn @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "发布失败，mvn 退出码 $LASTEXITCODE" }
}
finally {
    Pop-Location
}
Write-Host '发布流程结束。若未开启 -AutoPublish，请到 Central Portal 的 Deployments 页面确认发布。'
