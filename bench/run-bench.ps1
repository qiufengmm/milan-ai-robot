# 在 PowerShell 里跑 Token 探针
#
# 用法：
#   .\run-bench.ps1                # 跑默认 3 个 query（需要 SearXNG 在 localhost:8888）
#   .\run-bench.ps1 -Selftest      # 只做分词器自证，不联网、不需要 Docker
#
# 前置：JAVA_HOME 指向 JDK 21（未设置时脚本给一个本机默认值）

param(
    [switch]$Selftest,
    [string]$JavaHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

if (-not $JavaHome -or -not (Test-Path $JavaHome)) {
    $JavaHome = 'C:\Program Files\Java\jdk-21.0.10'
}
if (-not (Test-Path $JavaHome)) {
    throw "找不到 JDK：$JavaHome，请用 -JavaHome 指定或设置 JAVA_HOME"
}

$benchDir = $PSScriptRoot
$projDir = Split-Path $benchDir -Parent
$bootDir = Join-Path $projDir 'milan-ai-robot-springboot'
$cpFile = Join-Path $benchDir '.classpath.txt'
$mvn = 'D:\apache-maven-3.9.16-bin\apache-maven-3.9.16\bin\mvn'

# 1. 导出依赖 classpath（只需在依赖变动后重新生成；这里每次跑都刷一遍，省得忘记）
if (-not (Test-Path $cpFile) -or ((Get-Item $cpFile).LastWriteTime -lt (Get-Date).AddDays(-1))) {
    Write-Host '[1/3] 导出依赖 classpath ...' -ForegroundColor Cyan
    Push-Location $bootDir
    try {
        & $mvn -o -q dependency:build-classpath "-Dmdep.outputFile=$cpFile"
    } finally {
        Pop-Location
    }
}

$cp = (Get-Content $cpFile -Raw).Trim()

# 2. 编译
Write-Host '[2/3] 编译 TokenBench ...' -ForegroundColor Cyan
& "$JavaHome\bin\javac" -encoding UTF-8 -nowarn -cp $cp -d (Join-Path $benchDir 'classes') (Join-Path $benchDir 'TokenBench.java')

# 3. 运行
Write-Host '[3/3] 运行 ...' -ForegroundColor Cyan
$fullCp = (Join-Path $benchDir 'classes') + ';' + $cp
if ($Selftest) {
    & "$JavaHome\bin\java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp $fullCp TokenBench --selftest
} else {
    & "$JavaHome\bin\java" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -cp $fullCp TokenBench
}
