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
# 这个自检只覆盖**岗位模块**（功能二）——它的核心逻辑是零框架依赖的纯 Java，
# 所以能用 javac + 打桩在无网络环境下跑完 102 项断言。
#
# 其余模块都要真实依赖，一律排除：
#   material  —— 依赖 Apache POI / Apache PDFBox 解析文档，没有打桩（打桩要几十个类，得不偿失）
#   interview —— 依赖 Spring MVC 的控制器与语音适配器
#   resume    —— 依赖 Spring（配置绑定）并且要读 material 的画像
# 它们的正确性由真实构建验证：
#   mvn -B test                  102 项单元/集成测试
#   tools/material-check.ps1     用真实简历样本跑一遍抽取，人工核对结果
$mainSources = Get-ChildItem -Path (Join-Path $backend 'src\main\java') -Recurse -File -Filter *.java |
    Where-Object { $_.FullName -notmatch '\\(material|interview|resume)\\' }
$files += $mainSources.FullName
$files += (Get-ChildItem -Path (Join-Path $backend 'tools\verify-stubs') -Recurse -File -Filter *.java).FullName
# src/test 里只取**不依赖 JUnit** 的离线自检（JobAgentOfflineDemo）。
# 其余测试都基于 JUnit 5，需要 Maven 拉依赖；离线 javac 环境里没有 JUnit，
# 硬编进来只会一堆 "package org.junit.jupiter.api does not exist"。
# 那些测试由 `mvn test` 负责。
$files += (Get-ChildItem -Path (Join-Path $backend 'src\test\java') -Recurse -File -Filter 'JobAgentOfflineDemo.java').FullName
$files = @($files | Where-Object { $_ -notmatch '\\(interview|resume)\\' })

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
