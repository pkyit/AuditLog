# 计算模块的「代码语义指纹」：反汇编所有 class 并剥离行号信息后哈希。
#
# 用途：验证一次改动是否**只动了注释**。
#   注释变化  -> 指纹不变
#   语义变化  -> 指纹变化
#
# 为什么不用 class 文件字节哈希：javac 默认写入 LineNumberTable，
# 增删注释行会改变行号，字节哈希随之变化，无法区分"改注释"与"改代码"。
# 剥离行号表后，行号差异被消除，而字节码指令序列保持敏感。
param(
    [Parameter(Mandatory = $true)][string]$Module,
    [string]$ClassesDir
)

$ErrorActionPreference = 'Stop'
$javap = 'C:\Program Files\Java\jdk-17\bin\javap.exe'
if (-not (Test-Path $javap)) { throw "未找到 javap: $javap" }

if (-not $ClassesDir) { $ClassesDir = Join-Path $Module 'target/classes' }
if (-not (Test-Path $ClassesDir)) { throw "目录不存在: $ClassesDir（先执行 mvn compile）" }

$classes = Get-ChildItem -Recurse -Path $ClassesDir -Filter '*.class' | Sort-Object FullName
if ($classes.Count -eq 0) { throw "无 class 文件: $ClassesDir" }

$sb = New-Object System.Text.StringBuilder
foreach ($c in $classes) {
    [void]$sb.AppendLine("### " + $c.FullName.Substring($ClassesDir.Length))
    $dump = & $javap -p -c -constants $c.FullName 2>&1
    foreach ($line in $dump) {
        $t = $line.Trim()
        # 丢弃行号表与其内容：这些会随注释增删而变化，但不影响语义
        if ($t -eq 'LineNumberTable:') { continue }
        if ($t -match '^line \d+: \d+$') { continue }
        [void]$sb.AppendLine($line)
    }
}

$bytes = [System.Text.Encoding]::UTF8.GetBytes($sb.ToString())
$sha = [System.Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
$hex = ($sha | ForEach-Object { $_.ToString('x2') }) -join ''
Write-Output ("{0}  类数={1}  指纹={2}" -f $Module, $classes.Count, $hex.Substring(0, 32))
