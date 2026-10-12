# 拾阶求职工作台 · 岗位模块离线自检（Windows / PowerShell）
#
# 用途：在没有 Maven、装不上依赖、甚至没有外网的机器上，
#       直接用 JDK 自带的 javac 编译岗位模块的全部代码并跑端到端自检。
#
# 原理：core 层不依赖 Spring；Spring / Jackson / Jakarta 的注解与类型
#       由 tools/verify-stubs 下的编译期打桩替代。打桩只在本次校验中使用，
#       不在 src/main/java 里，不会被 Maven 打包。
#
# 用法：在 backend 目录下执行  powershell -ExecutionPolicy Bypass -File tools\verify.ps1

$ErrorActionPreference = 'Continue'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$backend = Split-Path -Parent $PSScriptRoot
$out = Join-Path $PSScriptRoot '.verify-out'

# 找 javac：优先 JAVA_HOME，其次 PATH
$javac = 'javac'
$java = 'java'
if ($env:JAVA_HOME) {
    $candidate = Join-Path $env:JAVA_HOME 'bin\javac.exe'
    if (Test-Path $candidate) {
        $javac = $candidate
        $java = Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
}

if (Test-Path $out) { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $out | Out-Null

$files = @()
$files += (Get-ChildItem -Path (Join-Path $backend 'src\main\java') -Recurse -File -Filter *.java).FullName
$files += (Get-ChildItem -Path (Join-Path $backend 'tools\verify-stubs') -Recurse -File -Filter *.java).FullName
$files += (Get-ChildItem -Path (Join-Path $backend 'src\test\java') -Recurse -File -Filter *.java).FullName

Write-Host "编译 $($files.Count) 个文件 ..."
& $javac -encoding UTF-8 -d $out $files 2>&1 | Out-String -Width 400 | Write-Host
if ($LASTEXITCODE -ne 0) {
    Write-Host "编译失败" -ForegroundColor Red
    exit 1
}
Write-Host "编译通过" -ForegroundColor Green

# 把资源放进 classpath，CSV 岗位库来源要靠它
Copy-Item -Recurse -Force (Join-Path $backend 'src\main\resources\*') $out

Write-Host ""
& $java '-Dfile.encoding=UTF-8' -cp $out com.shijie.resumehelper.job.JobAgentOfflineDemo
exit $LASTEXITCODE
