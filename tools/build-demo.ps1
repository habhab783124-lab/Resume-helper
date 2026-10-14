# =============================================================================
#  重新打一份「拾阶-演示包」
#
#  为什么要这个脚本：
#    演示包 = 后端 fat jar + 前端静态文件 + 便携 JRE + 启动脚本。
#    手动打容易漏（例如忘了重新构建前端 dist，包里的页面就是旧的），
#    而且 JRE 复制、zip 分隔符这些细节踩过坑，写下来比较稳。
#
#  用法（在仓库根目录）：
#    powershell -ExecutionPolicy Bypass -File tools\build-demo.ps1
#    powershell -ExecutionPolicy Bypass -File tools\build-demo.ps1 -JreSource "C:\some\jre"
#
#  产出：
#    <OutDir>\拾阶-演示包\        （文件夹，167 MB 左右）
#    <OutDir>\拾阶-演示包.zip     （便于分享，80 MB 左右）
#
#  说明：
#    - 便携 JRE 优先取 -JreSource；没给就在旧演示包里找一个复用；
#      再没有就用 JDK 自带的 jlink 生成一个精简运行时。
#      这样重复打包不用每次重新下载 JRE。
#    - 打包前会清掉 app\data（里面是画像、材料、简历版本这类运行数据），
#      否则会把上一个用户的数据发给别人。
# =============================================================================

[CmdletBinding()]
param(
    # 输出目录，默认 C:\dev
    [string]$OutDir = "C:\dev",
    # 便携 JRE 的来源目录（里面要有 bin\java.exe）。不给就自动找。
    [string]$JreSource = "",
    # 有旧演示包时优先复用它里面的 JRE，省得重复拷贝一份 125 MB
    [string]$ReuseJreFrom = "C:\dev\demo\jre",
    # 打好包后是否实际启动一次做烟测
    [switch]$SkipSmokeTest
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# 路径都基于脚本位置往上找仓库根，不依赖调用者的当前目录
$repo = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$backend = Join-Path $repo 'backend'
$frontend = Join-Path $repo 'frontend'
$stage = Join-Path $OutDir '拾阶-演示包'
$zip = Join-Path $OutDir '拾阶-演示包.zip'

function Step($text) { Write-Host "`n=== $text ===" -ForegroundColor Cyan }
function Ok($text) { Write-Host "  [OK] $text" -ForegroundColor Green }
function Warn($text) { Write-Host "  [!!] $text" -ForegroundColor Yellow }

Write-Host "仓库根目录: $repo"

# ------------------------------------------------------------------ 1. 后端
# 先找 Maven：环境变量 → PATH → 常见的本地安装位置
Step "1/5 构建后端 fat jar"
$mvn = $null
foreach ($candidate in @(
        $env:MAVEN_HOME, $env:M2_HOME,
        'C:\dev\tools\apache-maven-3.9.9'
    )) {
    if ($candidate) {
        $exe = Join-Path $candidate 'bin\mvn.cmd'
        if (Test-Path $exe) { $mvn = $exe; break }
    }
}
if (-not $mvn) {
    $onPath = Get-Command mvn.cmd -ErrorAction SilentlyContinue
    if ($onPath) { $mvn = $onPath.Source }
}
if (-not $mvn) { throw "找不到 mvn.cmd。请装 Maven，或用 MAVEN_HOME 指定。" }
Ok "mvn: $mvn"

# 注意不要加 clean：clean 会删掉 target，而正在跑的 spring-boot:run 可能正锁着它
Push-Location $backend
try {
    & $mvn -B -ntp -DskipTests package | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "后端打包失败" }
} finally { Pop-Location }

$jar = Get-ChildItem (Join-Path $backend 'target\resume-helper-backend-*.jar') |
    Where-Object { $_.Name -notmatch 'sources|javadoc|original' } |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $jar) { throw "target 里没有找到后端 jar" }
Ok "$($jar.Name)  ($([math]::Round($jar.Length / 1MB, 1)) MB)"

# ------------------------------------------------------------------ 2. 前端
Step "2/5 构建前端静态文件"
$npm = (Get-Command npm.cmd -ErrorAction SilentlyContinue).Source
if (-not $npm) { throw "找不到 npm.cmd。请装 Node.js 18+。" }
Push-Location $frontend
try {
    & $npm run build | Out-Host
    if ($LASTEXITCODE -ne 0) { throw "前端构建失败" }
} finally { Pop-Location }

$dist = Join-Path $frontend 'dist'
if (-not (Test-Path (Join-Path $dist 'index.html'))) { throw "前端 dist 没有 index.html" }
Ok "dist: $((Get-ChildItem $dist -Recurse -File | Measure-Object).Count) 个文件"

# ------------------------------------------------------------------ 3. 组装
Step "3/5 组装目录"
foreach ($p in @($stage, $zip)) {
    if (Test-Path $p) { Remove-Item $p -Recurse -Force }
}
New-Item -ItemType Directory -Force -Path (Join-Path $stage 'app\webapp') | Out-Null

Copy-Item $jar.FullName (Join-Path $stage 'app') -Force
Ok "app\$($jar.Name)"

# 后端会托管 webapp/ 下的静态文件（见 SpaForwardController）
Copy-Item (Join-Path $dist '*') (Join-Path $stage 'app\webapp') -Recurse -Force
Ok "app\webapp\"

# ------------------------------------------------------------------ 4. JRE
Step "4/5 准备便携 JRE"
$jreTarget = Join-Path $stage 'jre'
$source = $null
foreach ($candidate in @($JreSource, $ReuseJreFrom)) {
    if ($candidate -and (Test-Path (Join-Path $candidate 'bin\java.exe'))) { $source = $candidate; break }
}

if ($source) {
    Copy-Item $source $jreTarget -Recurse -Force
    Ok "复用 $source"
} else {
    # 没有可复用的就用 jlink 生成精简运行时。
    # 模块列表是按运行时实测补出来的：POI 需要 java.desktop / java.sql，
    # PDFBox 与字体渲染需要 java.desktop，Spring 需要 java.naming / jdk.httpserver。
    $jlink = Join-Path $env:JAVA_HOME 'bin\jlink.exe'
    if (-not (Test-Path $jlink)) { throw "既没有可复用的 JRE，也找不到 jlink（JAVA_HOME=$env:JAVA_HOME）" }
    Warn "没有可复用的 JRE，改用 jlink 生成（这个组合没经过完整功能验证，跑完请自己点一遍四个页面）"
    & $jlink --add-modules java.base,java.logging,java.naming,java.desktop,java.sql,java.xml,java.net.http,jdk.httpserver,jdk.crypto.ec,jdk.unsupported,jdk.zipfs `
        --strip-debug --no-header-files --no-man-pages --compress=zip-6 --output $jreTarget
    if ($LASTEXITCODE -ne 0) { throw "jlink 失败" }
}
Ok "jre\ ($([math]::Round(((Get-ChildItem $jreTarget -Recurse -File | Measure-Object Length -Sum).Sum / 1MB), 1)) MB)"

# 启动脚本：仓库里存一份模板，避免每次手写
$launcher = Join-Path $repo 'tools\demo-launcher.cmd'
if (-not (Test-Path $launcher)) { throw "缺少启动脚本模板：$launcher" }
Copy-Item $launcher (Join-Path $stage '启动演示.cmd') -Force
Ok "启动演示.cmd"

$manual = Join-Path $repo 'tools\demo-manual.md'
if (Test-Path $manual) {
    Copy-Item $manual (Join-Path $stage '演示操作手册.md') -Force
    Ok "演示操作手册.md"
} else {
    Warn "没有 tools\demo-manual.md，包里将不含操作手册"
}

# 运行数据绝不能打包进去
foreach ($dataDir in @((Join-Path $stage 'app\data'), (Join-Path $stage 'data'))) {
    if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
}
$stray = Get-ChildItem $stage -Recurse -Directory -Filter 'data' -ErrorAction SilentlyContinue
if ($stray) { throw "仍有运行数据目录残留：$($stray.FullName -join ', ')" }
Ok "确认没有把运行数据（画像 / 材料 / 简历版本）打进包"

# ------------------------------------------------------------------ 5. 压缩
Step "5/5 压缩成 zip"
# 必须用 bsdtar 而不是 Compress-Archive：
#   Compress-Archive（PowerShell 5.1 走 .NET Framework）会把条目名写成反斜杠，
#   而 ZIP 规范要求正斜杠。Windows 资源管理器能忍，但严格的解压工具会解出
#   一堆名字里带反斜杠的文件，目录结构就塌了。
& tar.exe -a -c -f $zip -C $OutDir '拾阶-演示包'
if ($LASTEXITCODE -ne 0) { throw "tar 打包失败" }

Add-Type -AssemblyName System.IO.Compression.FileSystem
$z = [System.IO.Compression.ZipFile]::OpenRead($zip)
try {
    $bad = ($z.Entries | Where-Object { $_.FullName -match '\\' }).Count
    if ($bad -gt 0) { throw "zip 里有 $bad 个条目用了反斜杠路径" }
    Ok "zip 条目 $($z.Entries.Count) 个，全部使用正斜杠"
} finally { $z.Dispose() }
Ok "$zip  ($([math]::Round((Get-Item $zip).Length / 1MB, 1)) MB)"

# ------------------------------------------------------------------ 烟测
if (-not $SkipSmokeTest) {
    Step "烟测：直接用包里的便携 JRE 启动一次"
    $port = 8099
    $proc = Start-Process -FilePath (Join-Path $jreTarget 'bin\java.exe') `
        -ArgumentList '-jar', $jar.Name, "--server.port=$port" `
        -WorkingDirectory (Join-Path $stage 'app') -WindowStyle Hidden -PassThru
    try {
        $ready = $false
        for ($i = 0; $i -lt 40; $i++) {
            Start-Sleep -Seconds 1
            & curl.exe -s -o NUL --max-time 2 "http://localhost:$port/api/jobs/sources" 2>$null
            if ($LASTEXITCODE -eq 0) { $ready = $true; break }
        }
        if (-not $ready) { throw "服务 40 秒内没有就绪" }
        Ok "接口就绪"

        foreach ($path in @('/', '/resume', '/api/materials', '/api/resume')) {
            $code = & curl.exe -s -o NUL -w "%{http_code}" --max-time 10 "http://localhost:$port$path" 2>$null
            if ($code -ne '200') { throw "$path 返回 $code" }
            Ok "$path -> 200"
        }
        # 关键：不存在的接口必须还是 404，不能被 SPA 兜底成 200
        $code = & curl.exe -s -o NUL -w "%{http_code}" --max-time 10 "http://localhost:$port/api/nope" 2>$null
        if ($code -ne '404') { throw "/api/nope 返回 $code，SPA 兜底规则可能把 API 的 404 吃掉了" }
        Ok "/api/nope -> 404（API 的 404 没有被 SPA 兜成 200）"

        # 首页必须是真的前端页面
        $html = & curl.exe -s --max-time 10 "http://localhost:$port/" 2>$null | Out-String
        # 单引号字符串：消息里带双引号，用双引号包会被 PowerShell 当成字符串结束
        if ($html -notmatch 'id="app"') { throw '首页不含 <div id="app">，静态资源可能没打包进去' }
        Ok "首页是前端页面"
    } finally {
        if ($proc -and -not $proc.HasExited) { Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue }
        Start-Sleep -Seconds 2
        # 烟测会创建 data 目录，删掉它，否则发出去的包带着测试数据
        foreach ($dataDir in @((Join-Path $stage 'app\data'), (Join-Path $stage 'data'))) {
            if (Test-Path $dataDir) { Remove-Item $dataDir -Recurse -Force }
        }
    }
}

Write-Host "`n完成。" -ForegroundColor Green
Write-Host "  文件夹: $stage"
Write-Host "  zip   : $zip"
Write-Host "`n  发给同学时用 zip；自己演示用文件夹（解压过的更快）。"
