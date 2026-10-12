<#
  拾阶求职工作台 · 岗位模块 HTTP 冒烟测试

  用途：后端启动之后，把功能二的所有接口按真实 HTTP 请求打一遍，
        验证 Spring MVC 接线、Jackson 序列化、参数绑定、异常处理都是对的。
        （离线自检 JobAgentOfflineDemo 覆盖不到这一层，因为它不起 Web 服务。）

  前置：后端已启动，且能看到这一行日志：
        Tomcat started on port 8081

  用法：
      powershell -ExecutionPolicy Bypass -File tools\smoke-api.ps1
      powershell -ExecutionPolicy Bypass -File tools\smoke-api.ps1 -BaseUrl http://localhost:8081
      powershell -ExecutionPolicy Bypass -File tools\smoke-api.ps1 -ApiKey sk-xxxx

  带 -ApiKey 时会在请求头里带上用户自己的 token，用来验证真实大模型链路。

  --------------------------------------------------------------------------
  本脚本踩过的两个 PowerShell 5.1 坑（都已处理，改脚本时别踩回去）：

  1) 响应编码：PS 5.1 在 Content-Type 缺少 charset 时会按 ISO-8859-1 解码响应体，
     所有中文变成 "å²ä½åº"。服务端发的其实是合法 UTF-8（JSON 规范默认就是 UTF-8），
     浏览器和 axios 都正常。所以这里不用 Invoke-RestMethod，而是取原始字节自行按 UTF-8 解码。

  2) 单元素数组：PS 5.1 的 ConvertFrom-Json 会把长度为 1 的数组拆成标量，
     此时 `.Count` 不可靠。所有计数统一走 CountOf 包一层数组。
  --------------------------------------------------------------------------
#>
param(
    [string]$BaseUrl = 'http://localhost:8080',
    [string]$ApiKey = '',
    [string]$Model = '',
    [string]$BaseAiUrl = ''
)

$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

$script:passed = 0
$script:failed = 0

function Write-Section([string]$title) {
    Write-Host ""
    Write-Host $title
    Write-Host ('-' * 64)
}

function Check([string]$desc, [bool]$ok, [string]$detail = '') {
    if ($ok) {
        $script:passed++
        Write-Host "  [通过] $desc"
    } else {
        $script:failed++
        Write-Host "  [失败] $desc" -ForegroundColor Red
    }
    if ($detail) { Write-Host "         $detail" -ForegroundColor DarkGray }
}

<#
  统一计数。PS 5.1 的 ConvertFrom-Json 会把单元素数组拆成标量，
  对那个标量直接取 .Count 可能得到 $null，所以必须先包一层数组。
#>
function CountOf($value) {
    if ($null -eq $value) { return 0 }
    return @($value).Count
}

function New-AiHeaders {
    $h = @{}
    if ($ApiKey) { $h['X-AI-Api-Key'] = $ApiKey }
    if ($Model) { $h['X-AI-Model'] = $Model }
    if ($BaseAiUrl) { $h['X-AI-Base-Url'] = $BaseAiUrl }
    return $h
}

<#
  发一次请求，返回 @{ Status = HTTP 状态码; Json = 解析后的对象（可能为 $null）; Error = 传输层错误 }。
  不会因为 4xx/5xx 抛异常，方便断言错误响应。
#>
function Invoke-ApiRaw {
    param(
        [string]$Method,
        [string]$Path,
        $Payload,
        [hashtable]$Headers = @{}
    )
    $params = @{
        Uri             = "$BaseUrl$Path"
        Method          = $Method
        Headers         = $Headers
        UseBasicParsing = $true
        TimeoutSec      = 30
    }
    if ($null -ne $Payload) {
        # 中文必须显式转成 UTF-8 字节，否则 PS 5.1 会按 ANSI 发出去，后端收到乱码
        $params['Body'] = [System.Text.Encoding]::UTF8.GetBytes(($Payload | ConvertTo-Json -Depth 12))
        $params['ContentType'] = 'application/json; charset=utf-8'
    }

    try {
        $response = Invoke-WebRequest @params
        $bytes = $response.RawContentStream.ToArray()
        $json = $null
        if ($bytes.Length -gt 0) {
            $json = ConvertFrom-Json ([System.Text.Encoding]::UTF8.GetString($bytes))
        }
        return @{ Status = [int]$response.StatusCode; Json = $json; Error = $null }
    } catch {
        $webResponse = $_.Exception.Response
        if ($null -eq $webResponse) {
            # 连不上、超时之类，压根没有 HTTP 响应
            return @{ Status = 0; Json = $null; Error = $_.Exception.Message }
        }
        $status = [int]$webResponse.StatusCode
        $json = $null
        try {
            $reader = New-Object System.IO.StreamReader($webResponse.GetResponseStream(), [System.Text.Encoding]::UTF8)
            $body = $reader.ReadToEnd()
            $reader.Close()
            if ($body) { $json = ConvertFrom-Json $body }
        } catch {
            # 错误响应体读不出来也无所谓，至少还有状态码
        }
        return @{ Status = $status; Json = $json; Error = $null }
    }
}

function Invoke-Api {
    param(
        [string]$Method,
        [string]$Path,
        $Payload,
        [hashtable]$Headers = @{}
    )
    $result = Invoke-ApiRaw -Method $Method -Path $Path -Payload $Payload -Headers $Headers
    if ($result.Error) { throw "请求 $Path 失败：$($result.Error)" }
    return $result.Json
}

# ---------------------------------------------------------------- 前置检查

Write-Host "=== 岗位模块 HTTP 冒烟测试 ===" -ForegroundColor Cyan
Write-Host "目标：$BaseUrl"

$probe = Invoke-ApiRaw -Method Get -Path '/api/jobs/sources'
if ($probe.Error -or $probe.Status -ne 200) {
    Write-Host ""
    Write-Host "连不上后端（$($probe.Error)；HTTP $($probe.Status)）" -ForegroundColor Red
    Write-Host "请先在 backend 目录执行  mvn spring-boot:run -Dspring-boot.run.profiles=local  ，" -ForegroundColor Yellow
    Write-Host "等看到 'Tomcat started on port 8081' 再跑本脚本。" -ForegroundColor Yellow
    exit 1
}

$aiHeaders = New-AiHeaders
if ($ApiKey) {
    Write-Host "已启用用户自带 token（仅用于本次请求，不会回显）" -ForegroundColor Yellow
}

$profile = @{
    userId            = 'smoke-user'
    targetTitle       = 'Java 后端开发工程师'
    targetCities      = @('上海')
    yearsOfExperience = 0
    education         = '本科'
    skills            = @('Java', 'Spring Boot', 'MySQL', 'Redis')
    expectedSalary    = '15-22K'
    summary           = '计算机专业应届生，做过校园活动管理平台'
    experiences       = @('校园活动管理平台：负责报名与签到接口，用 Redis 缓存热门活动，响应从 800ms 降到 120ms')
}

$jobId = $null

# ---------------------------------------------------------------- 1. 数据源

Write-Section '1. GET /api/jobs/sources —— 数据源清单'
$sources = Invoke-Api -Method Get -Path '/api/jobs/sources'
Check 'success=true' ($sources.success -eq $true)
Check '至少装配了 1 个数据源' ((CountOf $sources.data) -ge 1) `
    ("实际 " + (CountOf $sources.data) + " 个：" + (($sources.data | ForEach-Object { $_.name }) -join '、'))
$mockSources = @($sources.data | Where-Object { $_.kind -eq 'mock' })
Check '演示岗位库存在（兜底保证）' ((CountOf $mockSources) -ge 1)

# ---------------------------------------------------------------- 2. 无画像列表

Write-Section '2. GET /api/jobs?limit=3 —— 不带画像，只列岗位'
$plain = Invoke-Api -Method Get -Path '/api/jobs?limit=3'
$plainJobs = @($plain.data.jobs)
Check 'success=true' ($plain.success -eq $true)
Check '返回了岗位' ((CountOf $plainJobs) -gt 0) ("实际 " + (CountOf $plainJobs) + " 条")
Check '遵守 limit=3' ((CountOf $plainJobs) -le 3)
Check '不带画像时 score 为 null' ($null -eq $plainJobs[0].score)
Check '带「不代表录取概率」说明' ($plain.data.scoreNotice -like '*不代表录取概率*')
Check '卡片有 id / 公司标记 / 配色类' `
    ((-not [string]::IsNullOrWhiteSpace($plainJobs[0].id)) -and ($plainJobs[0].mark.Length -eq 1)) `
    ("示例：" + $plainJobs[0].title + " @ " + $plainJobs[0].company)
$jobId = $plainJobs[0].id

# ---------------------------------------------------------------- 3. 带画像搜索（GET 扁平参数）

Write-Section '3. GET /api/jobs —— 扁平画像参数，应返回匹配度'
$flatQuery = '/api/jobs?keyword=Java&targetTitle=Java%20%E5%90%8E%E7%AB%AF%E5%BC%80%E5%8F%91%E5%B7%A5%E7%A8%8B%E5%B8%88' +
'&skills=Java,Spring%20Boot,MySQL,Redis&targetCities=%E4%B8%8A%E6%B5%B7&yearsOfExperience=0' +
'&education=%E6%9C%AC%E7%A7%91&expectedSalary=15-22K&limit=5'
$flat = Invoke-Api -Method Get -Path $flatQuery
$flatJobs = @($flat.data.jobs)
Check 'success=true' ($flat.success -eq $true)
Check '返回了带分数的岗位' ((CountOf $flatJobs) -gt 0 -and $null -ne $flatJobs[0].score)
Check '分数在 0-100 之间' ($flatJobs[0].score -ge 0 -and $flatJobs[0].score -le 100)
Check '有匹配理由' (-not [string]::IsNullOrWhiteSpace($flatJobs[0].reason))
$scores = @($flatJobs | ForEach-Object { $_.score })
$scoresSorted = @($scores | Sort-Object -Descending)
Check '结果按分数降序' (($scores -join ',') -eq ($scoresSorted -join ',')) ("实际顺序 " + ($scores -join ', '))

# ---------------------------------------------------------------- 4. POST 搜索

Write-Section '4. POST /api/jobs/search —— 画像放 body（推荐前端用这个）'
$search = Invoke-Api -Method Post -Path '/api/jobs/search' -Headers $aiHeaders `
    -Payload @{ keyword = 'Java'; limit = 5; profile = $profile }
$searchJobs = @($search.data.jobs)
Check 'success=true' ($search.success -eq $true)
Check '返回了岗位' ((CountOf $searchJobs) -gt 0)
Check 'total 与实际条数一致' ($search.data.total -eq (CountOf $searchJobs))
$maxTags = ($searchJobs | ForEach-Object { CountOf $_.tags } | Measure-Object -Maximum).Maximum
Check '技能标签不超过 4 个' ($maxTags -le 4) ("最多 " + $maxTags + " 个")
$jobId = $searchJobs[0].id
Check '拿到了岗位 id 供后续接口用' (-not [string]::IsNullOrWhiteSpace($jobId)) ("jobId = $jobId")

# ---------------------------------------------------------------- 5. 智能体

Write-Section '5. POST /api/jobs/agent/run —— 智能体自己决定怎么找'
$agent = Invoke-Api -Method Post -Path '/api/jobs/agent/run' -Headers $aiHeaders `
    -Payload @{ profile = $profile; options = @{ limit = 6; campusOnly = $false } }
$agentJobs = @($agent.data.jobs)
$agentTrace = @($agent.data.trace)
Check 'success=true' ($agent.success -eq $true)
Check '返回了岗位' ((CountOf $agentJobs) -gt 0)
Check '返回了执行轨迹' ((CountOf $agentTrace) -gt 0) ("轨迹 " + (CountOf $agentTrace) + " 步")
$searchSteps = @($agentTrace | Where-Object { $_.action -eq 'search_jobs' })
Check '轨迹记录了检索动作' ((CountOf $searchSteps) -ge 1)
$noObservation = @($agentTrace | Where-Object { [string]::IsNullOrWhiteSpace($_.observation) })
Check '每步都有观察结果' ((CountOf $noObservation) -eq 0)
Check '标注了是否真的用到大模型' ($null -ne $agent.data.aiUsed) `
    ("aiUsed = " + $agent.data.aiUsed + "，模型 = " + $agent.data.aiModel)

Write-Host ""
Write-Host "  智能体执行轨迹：" -ForegroundColor DarkCyan
foreach ($step in $agentTrace) {
    Write-Host ("    第 {0} 轮 [{1}] {2}" -f $step.round, $step.action, $step.observation) -ForegroundColor DarkCyan
}
$agentNotes = @($agent.data.notes)
if ((CountOf $agentNotes) -gt 0) {
    Write-Host "  降级提示（前端应原样展示）：" -ForegroundColor Yellow
    foreach ($note in $agentNotes) { Write-Host "    · $note" -ForegroundColor Yellow }
}
if ($ApiKey) {
    Check '带 token 时确实调用了大模型' ($agent.data.aiUsed -eq $true) `
        '若为 false，说明 token / base-url / model 有问题，看后端日志'
}

# ---------------------------------------------------------------- 6. 粘贴导入

Write-Section '6. POST /api/jobs/import —— 粘贴 JD 导入（没有爬虫数据时的兜底）'
$jd = @"
岗位名称：Java 后端开发工程师
公司名称：冒烟测试公司
薪资：16-24K
工作地点：上海浦东新区
任职要求：本科，应届生亦可；熟悉 Java、Spring Boot、MySQL、Redis；了解消息队列。
"@
$imported = Invoke-Api -Method Post -Path '/api/jobs/import' `
    -Payload @{ text = $jd; sourceUrl = 'https://example.com/job/smoke' }
$importedTags = @($imported.data.tags)
Check 'success=true' ($imported.success -eq $true)
Check '抽出了岗位名称' ($imported.data.title -like '*Java*') ("title = " + $imported.data.title)
Check '抽出了公司名称' ($imported.data.company -eq '冒烟测试公司') ("company = " + $imported.data.company)
Check '抽出了城市' ($imported.data.city -eq '上海') ("place = " + $imported.data.place)
Check '抽出了薪资' ($imported.data.salary -like '*16*') ("salary = " + $imported.data.salary)
Check '识别出了技能' ((CountOf $importedTags) -ge 3) ("tags = " + ($importedTags -join '、'))
Check '来源标注为手动导入' ($imported.data.sourceName -like '*手动*')
$importedId = $imported.data.id

# ---------------------------------------------------------------- 7. 详情

Write-Section '7. GET /api/jobs/{id} —— 岗位详情'
$detail = Invoke-Api -Method Get -Path "/api/jobs/$importedId"
Check 'success=true' ($detail.success -eq $true)
Check '详情带完整岗位描述' (-not [string]::IsNullOrWhiteSpace($detail.data.description))
Check '来源链接被保留（岗位可回溯）' ($detail.data.sourceUrl -eq 'https://example.com/job/smoke')

$missing = Invoke-Api -Method Get -Path '/api/jobs/job-not-exist'
Check '查不存在的 id 返回 success=false 而不是 500' ($missing.success -eq $false) ("message = " + $missing.message)

# ---------------------------------------------------------------- 8. 匹配与差距报告

Write-Section '8. POST /api/jobs/{id}/match —— 匹配度与技能差距报告'
$match = Invoke-Api -Method Post -Path "/api/jobs/$importedId/match" -Headers $aiHeaders `
    -Payload @{ profile = $profile; explainAi = $true }
$dimNames = @($match.data.dimensions.PSObject.Properties | ForEach-Object { $_.Name })
$matchedSkills = @($match.data.matchedSkills)
$suggestions = @($match.data.gapReport.suggestions)
Check 'success=true' ($match.success -eq $true)
Check '有匹配度分数' ($null -ne $match.data.score) ("score = " + $match.data.score)
Check '有各维度得分' ((CountOf $dimNames) -ge 5) ("维度 = " + ($dimNames -join '、'))
Check '有命中技能' ((CountOf $matchedSkills) -gt 0) ("命中 = " + ($matchedSkills -join '、'))
Check '差距报告非空' ($null -ne $match.data.gapReport)
Check '差距报告给了建议' ((CountOf $suggestions) -gt 0) ("建议 = " + ($suggestions -join ' / '))
Check '响应带「不代表录取概率」说明' ($match.data.scoreNotice -like '*不代表录取概率*')

# ---------------------------------------------------------------- 9. 收藏

Write-Section '9. 收藏接口'
$fav = Invoke-Api -Method Post -Path "/api/jobs/$importedId/favorite?userId=smoke-user"
Check '收藏成功' ($fav.success -eq $true)
$favList = Invoke-Api -Method Get -Path '/api/jobs/favorites?userId=smoke-user'
$favJobs = @($favList.data.jobs)
$favHit = @($favJobs | Where-Object { $_.id -eq $importedId })
Check '收藏列表包含该岗位' ((CountOf $favHit) -eq 1) ("列表共 " + (CountOf $favJobs) + " 条")
Check '收藏项 saved=true' ($favHit[0].saved -eq $true)
$unfav = Invoke-Api -Method Delete -Path "/api/jobs/$importedId/favorite?userId=smoke-user"
Check '取消收藏成功' ($unfav.success -eq $true)
$favList2 = Invoke-Api -Method Get -Path '/api/jobs/favorites?userId=smoke-user'
Check '取消后列表为空' ((CountOf $favList2.data.jobs) -eq 0)

# ---------------------------------------------------------------- 10. 参数校验

Write-Section '10. 参数校验与错误处理'
$badImport = Invoke-ApiRaw -Method Post -Path '/api/jobs/import' -Payload @{ text = '' }
Check '空 JD 返回 HTTP 400 而不是 500' ($badImport.Status -eq 400) ("实际 HTTP " + $badImport.Status)
Check '错误响应的中文能正常解码' `
    (($null -ne $badImport.Json) -and ($badImport.Json.success -eq $false) -and ($badImport.Json.message -like '*岗位描述*')) `
    ("message = " + $badImport.Json.message)

$badMatch = Invoke-ApiRaw -Method Post -Path '/api/jobs/job-not-exist/match' -Payload @{ profile = $profile }
Check '给不存在的岗位算匹配被干净拒绝' `
    ((($badMatch.Status -eq 200) -and ($badMatch.Json.success -eq $false)) -or ($badMatch.Status -eq 404)) `
    ("HTTP " + $badMatch.Status + "，message = " + $badMatch.Json.message)

# ---------------------------------------------------------------- 汇总

Write-Host ""
if ($script:failed -eq 0) {
    Write-Host "=== 冒烟测试通过：$script:passed 项，失败 0 项 ===" -ForegroundColor Green
    exit 0
} else {
    Write-Host "=== 冒烟测试结果：通过 $script:passed 项，失败 $script:failed 项 ===" -ForegroundColor Red
    exit 1
}
