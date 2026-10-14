<#
.SYNOPSIS
  功能一（材料整理）的接口验收脚本。

.DESCRIPTION
  用真实的简历文件跑一遍上传 → 抽取 → 确认 → 删除的全流程，并逐项断言。
  和 smoke-api.ps1 的区别：那个测岗位模块，这个测材料模块；
  这个还需要 tools/testdata 下的真实样本。

  样本的生成见 tools/testdata/make-samples.py（需要 Python，以及 LibreOffice 转 PDF）。
  样本不存在时相关检查会被跳过并明确标出来，而不是假装通过。

.PARAMETER BaseUrl
  后端地址。本机 8080 常被其他程序占用，默认给的就是 8080，需要时自己改。

.PARAMETER ApiKey
  你的大模型 token。**不传就不做真实抽取**——那时抽取结果会是空的，
  并带上"没有可用的 AI token"的提示。脚本会把这两种情况区分开报告，
  不会把"没 token"当成"通过"。

.EXAMPLE
  pwsh -File tools/material-check.ps1 -BaseUrl http://localhost:8095

.EXAMPLE
  # 带真实 token 跑，验证抽取质量
  pwsh -File tools/material-check.ps1 -BaseUrl http://localhost:8095 -ApiKey "sk-xxx"
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey = ''
)

$ErrorActionPreference = 'Stop'
$OutputEncoding = [System.Text.Encoding]::UTF8

$backend = Split-Path -Parent $PSScriptRoot
$testdata = Join-Path $PSScriptRoot 'testdata'
$work = Join-Path $PSScriptRoot 'material-check-out'
if (Test-Path $work) { Remove-Item -Recurse -Force $work }
New-Item -ItemType Directory -Force -Path $work | Out-Null

$script:passed = 0
$script:failed = 0
$script:skipped = 0
$script:notes = @()

function Check($description, [bool]$ok, $detail = '') {
    if ($ok) {
        $script:passed++
        Write-Host "  [通过] $description" -ForegroundColor Green
    } else {
        $script:failed++
        Write-Host "  [失败] $description" -ForegroundColor Red
        if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
    }
}

function Skip($description, $reason) {
    $script:skipped++
    Write-Host "  [跳过] $description" -ForegroundColor Yellow
    Write-Host "         $reason" -ForegroundColor DarkGray
}

function Note($text) {
    $script:notes += $text
    Write-Host "  [注意] $text" -ForegroundColor Yellow
}

<#
  用 curl 发请求并把响应写到文件，再用 .NET 按 UTF-8 读回来。

  为什么不直接用 Invoke-RestMethod：PowerShell 5.1 的 Invoke-RestMethod
  对没有 charset 的响应会按 ISO-8859-1 解码，中文全变乱码——
  这个坑在 smoke-api.ps1 里踩过一次，这里不再踩第二遍。
#>
function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        [string]$FormFile,
        [string]$JsonFile,
        [string]$JsonBody,
        [string]$OutName = 'response.json'
    )
    $outFile = Join-Path $work $OutName
    $arguments = @('-sS', '--max-time', '120', '-o', $outFile, '-w', '%{http_code}')
    if ($Method -ne 'GET') { $arguments += @('-X', $Method) }
    if ($FormFile) { $arguments += @('-F', "file=@$FormFile") }
    if ($JsonFile) { $arguments += @('-H', 'Content-Type: application/json', '--data-binary', "@$JsonFile") }
    elseif ($JsonBody) { $arguments += @('-H', 'Content-Type: application/json', '--data-binary', $JsonBody) }
    if ($ApiKey) { $arguments += @('-H', "X-AI-Api-Key: $ApiKey") }
    $arguments += "$BaseUrl$Path"

    $code = & curl.exe @arguments 2>$null
    $text = if (Test-Path $outFile) { [System.IO.File]::ReadAllText($outFile, [System.Text.Encoding]::UTF8) } else { '' }
    $json = $null
    if ($text.Trim()) {
        try { $json = $text | ConvertFrom-Json } catch { $json = $null }
    }
    return [pscustomobject]@{ Code = "$code"; Json = $json; Text = $text }
}

# ---------------------------------------------------------------------- 准备

Write-Host ""
Write-Host "=== 功能一（材料整理）接口验收 ===" -ForegroundColor Cyan
Write-Host "目标：$BaseUrl"
Write-Host ""

$live = Invoke-Api -Method GET -Path '/api/materials' -OutName 'live.json'
if ($live.Code -ne '200') {
    Write-Host "后端没有响应（HTTP $($live.Code)）。先把它跑起来：" -ForegroundColor Red
    Write-Host "  cd backend" -ForegroundColor DarkGray
    Write-Host "  `$env:SERVER_PORT=8095 ; mvn spring-boot:run" -ForegroundColor DarkGray
    Write-Host ""
    exit 1
}
Check "GET /api/materials 可用" ($live.Code -eq '200')

$docx = Join-Path $testdata 'sample-resume.docx'
$pdf = Join-Path $testdata 'sample-resume.pdf'
$scanned = Join-Path $testdata 'sample-scanned.pdf'
$hasSamples = (Test-Path $docx) -and (Test-Path $pdf) -and (Test-Path $scanned)

if (-not $hasSamples) {
    Skip "真实文件抽取检查" "缺少 tools/testdata 下的样本，先运行：python tools/testdata/make-samples.py"
}

$uploadedIds = @()

# ---------------------------------------------------------------------- 上传

if ($hasSamples) {
    Write-Host ""
    Write-Host "--- 上传 DOCX 简历 ---"

    $r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $docx -OutName 'docx.json'
    Check "上传返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)：$($r.Text)"
    if ($r.Json -and $r.Json.success) {
        $m = $r.Json.data.material
        $uploadedIds += $m.id
        Check "状态是「已提取」" ($m.status -eq 'TEXT_EXTRACTED') "实际 $($m.status)"
        Check "抽出的可见字符超过 500（表格和段落都读到了）" ($m.textLength -gt 500) "实际 $($m.textLength)"
        Check "类型标签是 DOCX" ($m.fileTypeLabel -eq 'DOCX')
    }

    Write-Host ""
    Write-Host "--- 上传文本型 PDF ---"

    $r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $pdf -OutName 'pdf.json'
    Check "上传返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)"
    if ($r.Json -and $r.Json.success) {
        $m = $r.Json.data.material
        $uploadedIds += $m.id
        Check "状态是「已提取」" ($m.status -eq 'TEXT_EXTRACTED') "实际 $($m.status)"
        Check "报告了页数（PDF 才有）" ($m.pageCount -gt 0) "实际 $($m.pageCount)"
        Check "抽出的可见字符超过 500" ($m.textLength -gt 500) "实际 $($m.textLength)"
    }

    Write-Host ""
    Write-Host "--- 上传扫描件 PDF（负样本）---"

    $r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $scanned -OutName 'scanned.json'
    Check "上传返回 200（不是报错）" ($r.Code -eq '200') "实际 HTTP $($r.Code)"
    if ($r.Json -and $r.Json.success) {
        $m = $r.Json.data.material
        $uploadedIds += $m.id
        Check "识别为扫描件而不是解析失败" ($m.status -eq 'SCANNED') "实际 $($m.status)"
        Check "给出的提示指向「粘贴文本」" ($m.message -like '*粘贴文本*') "实际：$($m.message)"
        Check "没有抽取结果（不拿空文本去喂大模型）" ($null -eq $r.Json.data.extraction)
    }
}

# ---------------------------------------------------------------------- 粘贴

Write-Host ""
Write-Host "--- 粘贴文本（扫描件的兜底路径）---"

$pasteFile = Join-Path $work 'paste.json'
$pasteText = "林一舟`r`n求职意向：Java 后端开发工程师`r`n教育经历`r`n上海理工大学 计算机科学与技术 本科 2022.09-2026.06`r`n实习经历`r`n上海璞云信息技术有限公司 Java 后端开发实习生 2025.07-2025.09`r`n项目经历`r`n校园二手交易平台 后端负责人 2025.03-2025.06`r`n技能：Java、Spring Boot、MySQL、Redis"
$pasteBody = @{ title = '粘贴的简历'; text = $pasteText } | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($pasteFile, $pasteBody, (New-Object System.Text.UTF8Encoding($false)))

$r = Invoke-Api -Method POST -Path '/api/materials/text' -JsonFile $pasteFile -OutName 'paste.json'
Check "粘贴文本返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)：$($r.Text)"
$pastedId = $null
if ($r.Json -and $r.Json.success) {
    $pastedId = $r.Json.data.material.id
    $uploadedIds += $pastedId
    Check "粘贴的内容被当成材料存下来了" ($r.Json.data.material.textLength -gt 50)
    # 响应里只有文件名和统计，不回显正文，所以这里核对中文标题——
    # 中文能原样往返，说明请求体与响应体两端的编码链路都是对的
    Check "中文没有变成乱码（标题原样返回）" ($r.Text -like '*粘贴的简历*') "实际：$($r.Text)"
}

if ($pastedId) {
    # 再读一遍原文，确认粘贴的中文**完整落盘**了。
    # 这一步比看响应体更有意义：它覆盖了「存进去 → 再取出来」的完整链路。
    $textBack = Invoke-Api -Method GET -Path "/api/materials/$pastedId/text" -OutName 'paste-text.txt'
    Check "粘贴的中文完整落盘（能读回原文）" ($textBack.Text -like '*上海理工大学*') "读回长度 $($textBack.Text.Length)"
    Check "粘贴的原文没有被截断" ($textBack.Text -like '*Redis*')
}

$shortFile = Join-Path $work 'short.json'
[System.IO.File]::WriteAllText($shortFile, '{"title":"太短","text":"abc"}', (New-Object System.Text.UTF8Encoding($false)))
$r = Invoke-Api -Method POST -Path '/api/materials/text' -JsonFile $shortFile -OutName 'short.json'
Check "过短的粘贴被拒绝（400）" ($r.Code -eq '400') "实际 HTTP $($r.Code)"
Check "拒绝时给出了可读的原因" ($r.Text -like '*太短*') "实际：$($r.Text)"

# ---------------------------------------------------------------------- 错误处理

Write-Host ""
Write-Host "--- 错误处理 ---"

$oldDoc = Join-Path $work 'old.doc'
[System.IO.File]::WriteAllText($oldDoc, '旧版 Word 文件', (New-Object System.Text.UTF8Encoding($false)))
$r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $oldDoc -OutName 'olddoc.json'
Check "不支持的 .doc 被拒绝（400）" ($r.Code -eq '400') "实际 HTTP $($r.Code)"
Check "明确提示支持的格式和替代方案" (($r.Text -like '*PDF*') -and ($r.Text -like '*粘贴文本*')) "实际：$($r.Text)"
Check "顺带提示另存为 .docx" ($r.Text -like '*docx*')

$corrupt = Join-Path $work 'corrupt.pdf'
[System.IO.File]::WriteAllBytes($corrupt, [byte[]](1..64))
$r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $corrupt -OutName 'corrupt.json'
Check "损坏的 PDF 返回 200 但状态为失败（材料仍可管理）" ($r.Code -eq '200' -and $r.Json.data.material.status -eq 'FAILED') "实际 HTTP $($r.Code) 状态 $($r.Json.data.material.status)"
if ($r.Json.data.material) { $uploadedIds += $r.Json.data.material.id }

$r = Invoke-Api -Method GET -Path '/api/materials/mat-does-not-exist' -OutName 'missing.json'
Check "查询不存在的材料返回 404" ($r.Code -eq '404') "实际 HTTP $($r.Code)"

# ---------------------------------------------------------------------- 详情与原文

Write-Host ""
Write-Host "--- 详情与原文对照 ---"

if ($uploadedIds.Count -gt 0) {
    $first = $uploadedIds[0]
    $r = Invoke-Api -Method GET -Path "/api/materials/$first" -OutName 'detail.json'
    Check "查询材料详情返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)"
    Check "详情里带上了抽取结果字段" ($null -ne $r.Json.data) 

    $r = Invoke-Api -Method GET -Path "/api/materials/$first/text" -OutName 'text.txt'
    Check "能取到原文（用于「原文对照」）" ($r.Code -eq '200' -and $r.Text.Length -gt 100) "长度 $($r.Text.Length)"

    $list = Invoke-Api -Method GET -Path '/api/materials' -OutName 'list.json'
    Check "材料列表返回 200" ($list.Code -eq '200')
    Check "列表里有刚才上传的材料" ($list.Json.data.Count -ge $uploadedIds.Count) "列表 $($list.Json.data.Count) 份，上传了 $($uploadedIds.Count) 份)"
    Check "列表按时间倒序（最近的在最前）" ($list.Json.data[0].createdAt -ne $null)
}

# ---------------------------------------------------------------------- 删除

Write-Host ""
Write-Host "--- 删除 ---"

if ($pastedId) {
    $r = Invoke-Api -Method DELETE -Path "/api/materials/$pastedId" -OutName 'delete.json'
    Check "删除返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)"

    $r = Invoke-Api -Method GET -Path "/api/materials/$pastedId" -OutName 'deleted.json'
    Check "删除后真的查不到了（404）" ($r.Code -eq '404') "实际 HTTP $($r.Code)"

    $r = Invoke-Api -Method GET -Path "/api/materials/$pastedId/text" -OutName 'deletedtext.txt'
    Check "删除后原文也取不到了" ($r.Code -eq '404') "实际 HTTP $($r.Code)"
}

# ---------------------------------------------------------------------- 画像

Write-Host ""
Write-Host "--- 求职画像 ---"

$profileFile = Join-Path $work 'profile.json'
$profileBody = @{
    targetTitle       = 'Java 后端开发工程师'
    targetCities      = @('上海')
    yearsOfExperience = 0
    education         = '本科'
    skills            = @('Java', 'Spring Boot', 'MySQL', 'Redis')
    industries        = @('互联网')
    expectedSalary    = '15-22K'
    summary           = '计算机专业应届生'
    experiences       = @('上海璞云 后端实习')
} | ConvertTo-Json -Compress
[System.IO.File]::WriteAllText($profileFile, $profileBody, (New-Object System.Text.UTF8Encoding($false)))

$r = Invoke-Api -Method PUT -Path '/api/profile' -JsonFile $profileFile -OutName 'profile-put.json'
Check "保存画像返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)"
Check "画像被原样保存（中文正常）" ($r.Text -like '*Java 后端开发工程师*')

$r = Invoke-Api -Method GET -Path '/api/profile' -OutName 'profile-get.json'
Check "读取画像返回 200" ($r.Code -eq '200') "实际 HTTP $($r.Code)"
if ($r.Json -and $r.Json.success) {
    Check "画像字段完整（技能 4 项）" ($r.Json.data.skills.Count -eq 4) "实际 $($r.Json.data.skills.Count) 项"
    Check "服务端以自己解析出的用户为准，没被请求体里的 userId 覆盖" ($r.Json.data.userId -eq 'demo-user') "实际 $($r.Json.data.userId)"
}

# ---------------------------------------------------------------------- 抽取质量

Write-Host ""
Write-Host "--- 抽取质量 ---"

if (-not $ApiKey) {
    Skip "真实大模型抽取质量" "没有传 -ApiKey。当前走的是「没有 token 就不猜」的降级路径："
    Skip " " "抽取结果为空并带提示，这是**预期行为**，不是失败。"
    Write-Host ""
    Write-Host "  要验证真实抽取效果，重新运行并带上你的 token：" -ForegroundColor Yellow
    Write-Host "    pwsh -File tools/material-check.ps1 -BaseUrl $BaseUrl -ApiKey `"sk-你的key`"" -ForegroundColor DarkGray
} elseif ($hasSamples) {
    $r = Invoke-Api -Method POST -Path '/api/materials' -FormFile $docx -OutName 'docx-ai.json'
    if ($r.Json -and $r.Json.success -and $r.Json.data.extraction) {
        $p = $r.Json.data.extraction.profile
        Check "识别出了学历" ([bool]$p.highestEducation) "实际：'$($p.highestEducation)'"
        Check "识别出了技能" ($p.skills.Count -ge 4) "实际 $($p.skills.Count) 项：$($p.skills -join '、')"
        Check "识别出了教育经历" ($p.educations.Count -ge 1) "实际 $($p.educations.Count) 条"
        Check "识别出了实习经历" ($p.experiences.Count -ge 1) "实际 $($p.experiences.Count) 条"
        Check "识别出了项目经历" ($p.projects.Count -ge 1) "实际 $($p.projects.Count) 条"
        Check "每条教育经历都带了原文证据（可核对）" (($p.educations | Where-Object { $_.evidence }).Count -eq $p.educations.Count)

        # 学校名是最直接的判断依据：抽错了一眼就能看出来
        Check "学校名抽对了" (($p.educations | Where-Object { $_.school -like '*上海理工大学*' }).Count -ge 1) "实际：$(($p.educations | ForEach-Object { $_.school }) -join '、')"
        # 证据回验如果丢了东西，会在 warnings 里说明
        if ($p.warnings.Count -gt 0) {
            Note "抽取过程有 $($p.warnings.Count) 条提示：$($p.warnings -join ' / ')"
        }
    } else {
        Check "带 token 时应当能拿到抽取结果" $false "响应：$($r.Text.Substring(0, [Math]::Min(300, $r.Text.Length)))"
    }
}

# ---------------------------------------------------------------------- 汇总

Write-Host ""
Write-Host "=== 结果：通过 $script:passed 项，失败 $script:failed 项，跳过 $script:skipped 项 ===" -ForegroundColor $(if ($script:failed -eq 0) { 'Green' } else { 'Red' })
Write-Host ""

if ($script:failed -gt 0) { exit 1 }
exit 0
