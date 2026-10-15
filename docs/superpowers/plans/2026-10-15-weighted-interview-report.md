# 面试逐题加权评分与证据反馈 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
> 2026-10-15：用户已确认实施。实现保留在 codex/weighted-interview-report 分支的工作区中，未提交或推送；分阶段检查与验证记录见文末。

**Goal:** 面试结束后，在面试报告中展示每题评分、自动确定的题目权重、加权总分以及有回答证据的具体反馈。

**Architecture:** 在现有 `skills/interview/` 下新增两份项目专用参考文件，分别约定提问前契约和结束后评分反馈，继续由同一面试 Agent 分阶段读取。出题时生成结构化题目和评分契约，后端在展示问题前锁定能力分类、难度、权重及评分标准。面试结束后模型按契约评估每题，Java 校验结果并计算逐题分数、权重百分比和总分；会话中缓存有效评估，报告页负责展示与显式失败重试。

**Tech Stack:** Java 17、Spring Boot 3.2.5、现有 Jackson ObjectMapper、JUnit 5；Vue 3、现有 node:test 和 Chrome 验收脚本。不增加运行时依赖、数据库或任务队列。

## Global Constraints

- 用户需求：“面试结束后放到面试报告里面，每个题目有评分权重”。
- 用户已选择：“系统按考察能力和难度自动分配，提问前确定”。
- 继续直接读取项目根目录 `skills/interview/`；保留原始 skill 文件与许可证。项目专用的判断与反馈规则新增到 references，Java 负责加载、验证、算术与状态保存，本地 README 说明接入。
- 继续一次一问、动态追问，面试期间不提前展示评分或答案要点。
- 沿用 1–10 题、用户归属校验、pendingAnswer、失败重试与会话过期机制。
- 语音朗读只使用问题正文；语音转写仍需用户确认后提交，按提交的文字评分。
- 评分只用于训练参考，评价可见回答，不把“未覆盖”当成“能力不足”，不编造经历和指标。
- 当前会话及报告仍为进程内状态，重启后不可恢复；报告缓存生命周期与原会话一致。
- 工作区已有设置页、语音凭据与客户端改动。实施前重新查看 git diff，保留这些改动，不重写整份 Interview.vue 或 interview.js。

## 现状与改动依据

已有 `InterviewAgent.InterviewReport`、`TurnFeedback`、`GET /api/interviews/{id}/report` 和 `frontend/src/views/Report.vue`，页面已有总分、优缺点与逐题亮点/建议。

当前缺口：

1. 出题仅返回纯文本，没有保存权重与提问前评分标准。
2. 总分由模型自由返回，Java 未按逐题分数重算；缺字段会默认给 75 分。
3. 逐题反馈按数组位置关联答案，缺少稳定的 questionId 对齐和证据校验。
4. 模型不可用时规则报告固定给 75 分及通用优缺点。
5. 刷新报告会再次调用模型，可能重复计费并改变结果。
6. 报告页没有权重、加权贡献、原回答、评分依据与待评分状态。

## Skill 组织决策

选择在现有 skill 目录内增加项目扩展，不新增独立的 interview-scoring skill，也不改写上游 SKILL.md。出题与评分共享同一份简历、会话和题目契约，属于同一条面试流程；单独建立可触发的 skill 暂时没有独立调用场景，还会增加两套入口的路由与维护。

计划结构：

```text
skills/interview/
├── SKILL.md                              # 保留上游入口
├── UPSTREAM.md                           # 保留原文件校验，说明本地扩展
├── README.md                             # 项目接入与分阶段加载说明
└── references/
    ├── question-and-scoring-contract.md   # 保留上游参考
    ├── review-and-retry.md                # 保留上游参考
    ├── project-question-contract.md       # 新增：提问前分类、难度、评分标准与 JSON
    └── project-scoring-and-feedback.md    # 新增：五维判分、证据反馈与 JSON
```

其他已有参考文件和许可证继续保留，目录图仅展示与此决策相关的文件。

加载由 Java 显式完成，不能只在目录里放文件后假定 Agent 会自动发现：

- 出题：现有 Grill 资料 + project-question-contract.md + 当前后端评分策略。
- 评分：现有 Review 资料 + project-scoring-and-feedback.md + 已冻结的逐题契约。
- Skill 中的“模型输出 JSON”描述模型与服务端之间的协议；面试界面和 TTS 仍只显示问题正文。

两份本地参考文件的边界：

1. project-question-contract.md 说明如何依据简历/岗位选择 CORE、RELATED、SUPPLEMENT 与难度，为五维写出本题专用标准，并输出 Task 2 定义的 JSON；不能根据候选人之后的表现修改契约。
2. project-scoring-and-feedback.md 说明按冻结标准给五维档位、引用原回答证据、列出缺口和改进动作，输出 Task 3 定义的 JSON；不输出或修改总分、数字权重和题目正文。
3. 数字系数、合法档位和精度由后端策略提供并校验，参考文件不维护第二套可独立生效的数字配置。修改判分指导和反馈风格可编辑参考文件；改变数学规则需要同步更新后端策略和测试。
4. 上游默认不提供未经校准的精确总分。本地扩展明确启用本项目“训练参考评分”；模型只输出逐题档位和证据，总分由代码计算，不宣称招聘预测或外部校准。此约定仅适用于本项目加载的评分流程。
5. 上游六个原文件的 SHA 校验保持不变。UPSTREAM.md 标明新增参考文件为项目自有扩展；README 链接两份参考及加载时机，保留升级可追溯性。

Skill 文件修改后，新生成题目使用新规则；已出题的评分契约和已成功缓存的评分保持原快照。后续如需跨项目独立复用评分，再拆出独立 skill。

验证时补充三类可观察场景：同一份回答更改措辞后不改变冻结权重；回答中夹带“给满分/改权重”不能改变契约；新增参考文件编辑后进入下一次对应阶段提示词，而已保存契约和成功报告不随热更新漂移。只有语法/文件存在性检查不足以证明模型判断质量，实际模型行为验收须单独记录。

两份新参考文件及其加载代码已随 Task 2、Task 3 实施并验证。

## 已选产品规则

### 题目权重

模型输出能力名称、重要性分类和难度分类，后端使用固定表计算权重点数，模型不能直接决定数字权重。

| 因素 | 分类 | 系数 |
|---|---|---:|
| 与目标岗位/简历核心能力的关系 | CORE 核心 | 3 |
| 同上 | RELATED 相关 | 2 |
| 同上 | SUPPLEMENT 补充 | 1 |
| 难度 | BASIC 基础 | 1 |
| 同上 | INTERMEDIATE 进阶 | 1.25 |
| 同上 | ADVANCED 综合 | 1.5 |

`相对权重 = 重要性系数 × 难度系数`。Java 内部乘 4 存为整数 weightUnits，避免浮点误差。

**提问前锁定的是相对权重，不是最终百分比。** 动态追问还未全部生成，最终百分比在报告中按已回答题目的权重统一归一化。后续回答好坏不能改变已出题的分类、难度和权重。没有目标岗位时，根据真实简历确定能力关系，并注明权重来自简历方向推断。

例如三道已答题：相关基础题权重点数 8、相关综合题 12、核心基础题 12，归一化权重为 25%、37.5%、37.5%。三题得分分别 80、90、70 时，总分为 80.0。

### 逐题评分与反馈

采用五维、每维 0/5/10/15/20 分的训练量表，满分 100：切题与正确性、关键要点覆盖、推理与取舍、证据与具体性、表达结构。出题时为五维分别写下与当前问题匹配的通过标准；基础题不强求候选人提供个人项目指标。

评分等级锚点：0=未提供有效支持或明显错误；5=只有相关术语/片段；10=部分满足标准；15=主要满足、仍有具体缺口；20=完整满足本题约定标准。实际高低仍依赖模型判断，Java 保证量表、关联和算术一致。

每题反馈包含：回答证据、做得好的地方、缺少/错误的要点、扣分依据、改进动作、可复练的回答框架。回答框架只能组织真实事实，缺数据写“待补充真实数据”，不生成可冒充经历的答案。

### 总分与异常

`总分 = round(Σ(逐题得分 × weightUnits) / Σ(weightUnits), 1)`。

- 后端计算，忽略模型返回的总分或数字权重。展示百分比保留两位小数，以最大余数法分配 10,000 个基点，使已答题的展示权重合计 100%。总分计算使用原始点数，不能使用四舍五入后的展示百分比。
- 提前结束：只给已回答内容的参考分，同时展示“已答 3/5 题，提前结束”；未回答题列出“未作答”，无分数、不作为 0 分计入。
- 已答但评分失败：保留已成功评分的题目，其余为“待评分”；所有已答题评分成功前，总分为 null，不能悄悄排除失败题后给总分。
- 无回答：总分 null，报告状态 EMPTY；明确说明尚无可评分内容。
- 模型不可用：展示问答与“评分暂不可用”，无默认 75 分和猜测出的优缺点。
- 成功评估按 questionId 缓存。刷新复用缓存；用户显式点击“重试评分”时，只重试未成功题目。成功题目保持原评分。

## 数据与接口契约

以下为计划新增/调整的签名，均属于本次实施范围：

```java
// InterviewQuestion.java，包 com.shijie.resumehelper.interview
public record InterviewQuestion(
        String text, String competency, Importance importance, Difficulty difficulty,
        String weightReason, List<Criterion> criteria, String rubricVersion) {
    public enum Importance { CORE, RELATED, SUPPLEMENT }
    public enum Difficulty { BASIC, INTERMEDIATE, ADVANCED }
    public enum Dimension { CORRECTNESS, COVERAGE, REASONING, EVIDENCE, CLARITY }
    public record Criterion(Dimension dimension, String expectation) {}
    public record Overview(String competency, Importance importance,
                           Difficulty difficulty, int weightUnits, String weightReason) {}
}

// InterviewAssessment.java，同包
public record InterviewAssessment(
        String questionId, Map<InterviewQuestion.Dimension, Integer> dimensionScores,
        List<String> evidence, List<String> missingPoints,
        String highlight, String improvement, String suggestedOutline, String aiModel) {}

// InterviewScoringPolicy.java，同包
public final class InterviewScoringPolicy {
    public static final String VERSION = "weighted-evidence-v1";
    public int weightUnits(InterviewQuestion.Importance importance,
                           InterviewQuestion.Difficulty difficulty);
    public int questionScore(Map<InterviewQuestion.Dimension, Integer> dimensions);
    public BigDecimal overallScore(List<Integer> scores, List<Integer> weights);
    public List<BigDecimal> percentages(List<Integer> weights);
}
```

上述类的 imports：java.util.List、java.util.Map、java.math.BigDecimal；集合必须做防御性复制。Question 构造时验证正文非空且不超过 500 字、能力/权重原因各不超过 200 字、criteria 必须恰好覆盖五个 Dimension 且各 expectation 为 1–500 字。rubricVersion 由后端写入，不采信模型。

`InterviewService.Turn(questionId, question, answer)` 保持三字段，避免影响语音与提交协议。内部 State 新增：

```java
final Map<String, InterviewQuestion> questionContracts = new LinkedHashMap<>();
final Map<String, InterviewAssessment> assessments = new LinkedHashMap<>();
final Object reportLock = new Object();
InterviewAgent.InterviewReport cachedReport;
```

Session 响应增加 `Map<String, InterviewQuestion.Overview> questionMetadata`，仅包含公开分类和权重原因。完整 criteria 不返回活跃面试页面，避免泄露评分要点。

报告保留原字段并扩展，字段类型如下：

```java
// 替换 InterviewAgent 中原有记录；使用 java.math.BigDecimal。
public record InterviewReport(
        BigDecimal overallScore, String overallSummary,
        List<String> strengths, List<String> gaps, List<String> suggestions,
        List<TurnFeedback> turnFeedbacks, String aiModel,
        String reportStatus, String scoringVersion,
        int plannedCount, int answeredCount, int scoredCount) {}

public record TurnFeedback(
        int index, String question, Integer score, String highlight, String improvement,
        String questionId, String answer, String status,
        String competency, String difficulty, String importance,
        int weightUnits, BigDecimal weightPercent, BigDecimal weightedContribution,
        String weightReason, Map<InterviewQuestion.Dimension, Integer> dimensionScores,
        List<String> evidence, List<String> missingPoints, String suggestedOutline,
        String aiModel) {}
```

reportStatus 使用 COMPLETE / PARTIAL / UNAVAILABLE / EMPTY；题目 status 使用 SCORED / PENDING / UNANSWERED。COMPLETE 指已回答题目的评分齐全，是否完成全部面试由 answeredCount/plannedCount 另行判断。未回答题 weightPercent 和 weightedContribution 为 null；待评分题的 score 和 weightedContribution 为 null。

API：保留 GET `/api/interviews/{id}/report`，首次访问生成，后续返回缓存；新增 POST `/api/interviews/{id}/report/retry`，只重试失败评分。两者先验证本人和 FINISHED 状态。模型凭据由 Supplier 延迟解析，只在实际需要调用模型时读取；已有缓存不要求模型凭据有效。

## Task 1：实现可验证的权重和分数计算

**Files**

- Create: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewQuestion.java`
- Create: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewAssessment.java`
- Create: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewScoringPolicy.java`
- Create: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewScoringPolicyTest.java`

**Interfaces:** 使用上文三个类的完整字段和签名；供 Task 2 保存契约，供 Task 3 计算报告。

- [x] **Step 1：先写确定性算术与输入校验测试。** 核心断言：

```java
@Test void weightedScoreUsesOriginalWeights() {
    var policy = new InterviewScoringPolicy();
    assertEquals(new BigDecimal("80.0"),
            policy.overallScore(List.of(80, 90, 70), List.of(8, 12, 12)));
    assertEquals(List.of(new BigDecimal("25.00"), new BigDecimal("37.50"),
            new BigDecimal("37.50")), policy.percentages(List.of(8, 12, 12)));
}
@Test void roundedPercentagesStillSumToOneHundred() {
    var result = new InterviewScoringPolicy().percentages(List.of(1, 1, 1));
    assertEquals(new BigDecimal("100.00"), result.stream()
            .reduce(BigDecimal.ZERO, BigDecimal::add));
    assertEquals(List.of(new BigDecimal("33.34"), new BigDecimal("33.33"),
            new BigDecimal("33.33")), result);
}
```

另补：单题、全 0/全 100、空列表、长度不一致、非正权重、超范围分数；维度缺失/重复、非法档位；三个重要性与三个难度组合。

- [x] **Step 2：运行 RED。** `cd backend && mvn -Dtest=InterviewScoringPolicyTest test`；预期因新类型/方法缺失失败。
- [x] **Step 3：实现上述 records 及验证，计算核心使用以下逻辑。**

```java
public int weightUnits(InterviewQuestion.Importance importance,
                       InterviewQuestion.Difficulty difficulty) {
    int base = switch (importance) { case CORE -> 3; case RELATED -> 2; case SUPPLEMENT -> 1; };
    int factor = switch (difficulty) { case BASIC -> 4; case INTERMEDIATE -> 5; case ADVANCED -> 6; };
    return base * factor;
}
public int questionScore(Map<InterviewQuestion.Dimension, Integer> dimensions) {
    if (dimensions.size() != 5) throw new IllegalArgumentException("评分维度不完整");
    int result = 0;
    for (var key : InterviewQuestion.Dimension.values()) {
        Integer value = dimensions.get(key);
        if (value == null || value < 0 || value > 20 || value % 5 != 0)
            throw new IllegalArgumentException("维度分数无效");
        result += value;
    }
    return result;
}
public BigDecimal overallScore(List<Integer> scores, List<Integer> weights) {
    if (scores.isEmpty() || scores.size() != weights.size())
        throw new IllegalArgumentException("评分与权重不完整");
    long numerator = 0, denominator = 0;
    for (int i = 0; i < scores.size(); i++) {
        Integer score = scores.get(i), weight = weights.get(i);
        if (score == null || score < 0 || score > 100 || weight == null || weight <= 0)
            throw new IllegalArgumentException("评分或权重无效");
        numerator += (long) score * weight;
        denominator += weight;
    }
    return BigDecimal.valueOf(numerator)
            .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
}
```

percentages 的实现顺序固定：校验正权重→用 long 计算每项 `weight * 10000 / sum` 的商和余数→剩余基点按余数降序分发，同余按原题序→转 `BigDecimal.valueOf(basisPoints, 2)`。不使用二次浮点归一化。

- [x] **Step 4：运行 GREEN。** 同一测试命令全部通过。

## Task 2：在出题前锁定权重和评分契约

**Files**

- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewAgent.java`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewService.java`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewSkill.java`
- Create: `skills/interview/references/project-question-contract.md`
- Create: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewQuestionContractTest.java`
- Create: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewTestFixtures.java`
- Adapt fixtures: `InterviewFlowTest.java`、`InterviewModelTransportTest.java`、`InterviewSkillIntegrationTest.java`、`InterviewSkillFileLoadingTest.java`、`InterviewReportApiTest.java`、`UploadBoundaryApiTest.java`（均位于上述 test 包目录）。

**Interfaces:** 新增 `InterviewAgent.prepareQuestion(String resume, String role, List<InterviewService.Turn> history, int total, AiClient ai): InterviewQuestion`。保留 `nextQuestion` 作为 `prepareQuestion(...).text()` 的兼容入口，只保留一套生成逻辑。Service 的 start/answer 使用 prepareQuestion；Turn 仍为原三字段。

- [x] **Step 1：先测契约确实在回答前存在，并验证非法契约不会推进会话。** 新测试通过服务启动后检查 `session.questionMetadata().get(questionId)` 的 weightUnits，随后模拟下一题失败，比较失败前后 metadata 不变、pendingAnswer 保留且题数不推进。评分标准不得出现在 Session JSON 中。
- [x] **Step 2：运行 RED。** `mvn -Dtest=InterviewQuestionContractTest test`。
- [x] **Step 3：同一次模型调用返回题目与契约，Jackson 严格解析，schema 如下。** 不新增第二次单独的“给题目配权重”调用。

```json
{
  "question": "你如何确认缓存优化确实降低了延迟？",
  "competency": "性能优化与指标验证",
  "importance": "CORE",
  "difficulty": "INTERMEDIATE",
  "weightReason": "岗位核心能力，验证简历中的性能改进主张",
  "criteria": [
    {"dimension":"CORRECTNESS","expectation":"说明延迟指标及其含义"},
    {"dimension":"COVERAGE","expectation":"覆盖优化前后基线、样本和周期"},
    {"dimension":"REASONING","expectation":"解释如何排除其他变化的影响"},
    {"dimension":"EVIDENCE","expectation":"给出本人实际使用的测量方法，缺事实时承认待确认"},
    {"dimension":"CLARITY","expectation":"按结论、方法、结果清晰说明"}
  ]
}
```

模型输出不合法则返回现有 502 重试路径，不给未知分类静默补默认权重。忽略模型附带的 weight、overallScore 等未知字段；后端设置 VERSION。JSON 正文限 12,000 字符；使用现有 Jackson 依赖，不扩充原手写 SimpleJsonParser。

Service 中首题与后续题统一使用以下原子写入顺序，处于已有 state 同步区内：

```java
InterviewQuestion prepared = agent.prepareQuestion(state.resume, state.role, history, state.count, ai);
String nextId = UUID.randomUUID().toString();
// 只有完整解析与验证成功，才提交上一题的 answer、当前契约和新题。
state.questionContracts.put(nextId, prepared);
state.turns.add(new Turn(nextId, prepared.text(), null));
```

start 的 history 为 List.of()；answer 使用带原回答的历史副本。answer 的旧题更新和 pending 清空必须与新题提交处于同一临界区。finish 仅提交已保存的 pendingAnswer，不重新生成/推断契约。

project-question-contract.md 按上文边界编写，并包含此处的输入、分类原则和完整 JSON 示例。InterviewSkill.questionInstructions() 从项目文件系统显式加载它，仅在出题阶段使用；后端把 ScoringPolicy 的当前版本、分类系数和合法维度注入请求，避免规则仅靠模型记忆。项目适配提示词明确“输出结构化 JSON”；界面和 TTS 仍只收到问题正文。完整契约保存在 State，不回传 required criteria。测试 fixture 必须模拟结构化出题响应，仍保留 reasoning_content 不能成为正式题目的测试。

- [x] **Step 4：运行 GREEN 和原有会话、skill 文件热读取、传输、上传边界测试。** 文件热读取测试继续证明新文件内容进入下一次提示词；已经生成的题目契约保持原值。

## Task 3：结束后评分、后端聚合与可重试缓存

**Files**

- Create: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewReportAssembler.java`
- Create: `skills/interview/references/project-scoring-and-feedback.md`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewAgent.java`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewService.java`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewController.java`
- Modify: `backend/src/main/java/com/shijie/resumehelper/interview/InterviewSkill.java`
- Create: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewAssessmentTest.java`
- Create: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewReportAssemblerTest.java`
- Modify: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewReportApiTest.java`
- Modify: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewSkillIntegrationTest.java`
- Modify: `backend/src/test/java/com/shijie/resumehelper/interview/InterviewSkillFileLoadingTest.java`

**Interfaces**

```java
// 新增 InterviewAgent 的输入/返回类型与方法。
public record AssessmentInput(InterviewService.Turn turn, InterviewQuestion contract) {}
public Map<String, InterviewAssessment> assessAnswers(
        String resume, String role, List<AssessmentInput> inputs,
        List<InterviewService.Turn> fullHistory, AiClient ai);

// InterviewReportAssembler：校验、排序与汇总；不调用模型。
public InterviewAgent.InterviewReport assemble(
        int plannedCount, List<InterviewService.Turn> turns,
        Map<String, InterviewQuestion> contracts,
        Map<String, InterviewAssessment> assessments);

// InterviewService：替代旧 report 调用路径；旧 AiClient overload 可委托此方法。
public InterviewAgent.InterviewReport report(
        String owner, String id, java.util.function.Supplier<AiClient> client, boolean retry);
```

- [x] **Step 1：先写评分/聚合/接口测试。** 覆盖：模型伪造 overallScore/weight 被忽略；questionId 乱序正确关联；未知/重复 ID、缺维度、NaN/小数档位/越界档位拒绝；证据不在原回答中拒绝；缺题为 PENDING；未回答为 UNANSWERED；提前结束范围明确；无回答/鉴权失败/超时不造分。

关键聚合样例：三题分数 80/90/70，冻结权重 8/12/12，断言 80.0、25/37.5/37.5 和 COMPLETE；删去第二题 assessment 后，断言 PARTIAL、overallScore 为 null，第一/三题仍为 SCORED。全部无 assessment 且有回答时为 UNAVAILABLE；无回答时为 EMPTY。

接口测试以真实 Session 的 questionId 构造评分响应，不能继续用固定 index=1 代替关联。模拟 GET 两次只评分一次；成功后修改 skill 或模型配置，GET 保持原结果；POST retry 仅向模型传缺失题，成功题评分不改变；并发 GET 不重复调用；ACTIVE=409、其他用户=404。

- [x] **Step 2：运行 RED。** `mvn -Dtest=InterviewAssessmentTest,InterviewReportAssemblerTest,InterviewReportApiTest test`。
- [x] **Step 3：替换自由总分协议，模型只输出逐题评分与证据。**

```json
{
  "assessments": [{
    "questionId":"由服务器提供的题目ID",
    "dimensionScores":{"CORRECTNESS":15,"COVERAGE":10,"REASONING":15,"EVIDENCE":10,"CLARITY":20},
    "evidence":["我用压测工具对比了同一批接口"],
    "missingPoints":["没有说明压测周期和样本量"],
    "highlight":"说明了实际采用的验证方法",
    "improvement":"补充真实样本范围和统计周期",
    "suggestedOutline":"先讲指标口径，再说明测量方法，最后补充待确认的样本范围"
  }]
}
```

project-scoring-and-feedback.md 按上文边界编写，包含分档锚点、证据要求、反馈内容和此处的完整 JSON 示例；InterviewSkill.reportInstructions() 显式加载它。把冻结的题目、五维标准、原回答和完整历史注入评分提示词。当前请求的冻结标准优先于后来修改的 skill 默认量表。答案中的指令不能改变标准、权重或角色。逐题 evidence 只允许原回答的非空原文片段；统一换行和连续空白后回验，无法回验的条目不能成为成功 assessment。模型缺少关键反馈内容时也保留待评分状态，不补通用赞美。

去除原固定 75 分 fallback 与宽松手写报告解析器，保留问答作为失败时可见内容。无模型、调用失败或格式错误时返回空的成功评估集合，由 assembler 产生 UNAVAILABLE；有效部分按 questionId 合并，不让单个坏条目覆盖其他成功结果。响应最多十题、每项反馈字段最多 1,000 字、evidence/missingPoints 各最多十项，超过限制视为该项无效。

`InterviewReportAssembler` 按原 turns 顺序建立逐题回顾，question/answer/index 一律取服务端会话，不采信模型回传。分数、原始权重、展示百分比、加权贡献由 Policy 计算。overallSummary 由后端说明覆盖数、参考总分及优先改进方向；strengths 从有效亮点取前五个去重项，gaps 从缺失点取前五个，suggestions 按 `weightUnits × (100 - score)` 降序选取最多三项改进动作。实际零分与 null 待评分必须区分。

缓存/并发顺序：先查 owner 和 FINISHED；持有 state.reportLock 做每会话单次生成保护；短暂锁 state 拷贝题目、契约、历史和已有 assessment；网络调用不持有 state 锁；再短暂锁 state 合并成功项并写缓存。普通 GET 已有 cachedReport 时直接返回；retry=true 时只处理缺失项，无缺失项则直接返回。无回答不解析模型凭据。过期 state 仍受原查询规则限制。

Controller 的 GET 调用 `report(owner, id, () -> clients.resolve(request), false)`；新增 POST retry 使用相同 owner 和 Supplier，最后一参 true。保持 ApiResponse 外层结构。

- [x] **Step 4：运行 GREEN，确保所有失败路径保留原问答与 pending 提交结果。** 同时更新旧 75 分断言为 UNAVAILABLE/null，不再以“规则报告正常返回数字”作为通过标准。

## Task 4：报告页面展示、显式重试与完整验收

**Files**

- Modify: `frontend/src/views/Report.vue`
- Modify: `frontend/src/api/interview.js`（只增加 retryReport 方法，保留当前设置/语音凭据改动）
- Create: `frontend/src/api/interview-report.test.js`
- Modify: `frontend/src/api/interview.test.js`
- Modify: `frontend/tools/check-interview.mjs`
- Modify: `skills/interview/README.md`
- Modify: `skills/interview/UPSTREAM.md`（只标明项目新增文件，不修改上游六文件校验）
- Modify: `backend/docs/文字面试接口与接入.md`

**Interfaces:** 消费上文完整报告字段，沿用当前报告路由的 sessionId。API 新方法：

```javascript
retryReport: (id, ai) => request(`${sessionPath(id)}/report/retry`, {
  method: 'POST', ai, textModel: true
}),
```

- [x] **Step 1：按现有 interview-flow.test.js 的 Vue script setup 测试方式挂载 Report.vue。** 检查 COMPLETE 显示总分、PARTIAL/UNAVAILABLE/EMPTY 不显示 0 或“合格”；score=0 的有效评分仍显示 0；按 questionId 关联逐题内容；GET 加载与 POST 重试互不混用，重试失败仍保留已显示报告和问答。
- [x] **Step 2：运行 RED。** `cd frontend && node --test src/api/interview-report.test.js`。
- [x] **Step 3：改造现有模板。** 顶部展示加权训练参考分、已答/计划题数、已评分数；成功与提前结束分别说明范围。每题摘要展示能力、难度、权重百分比、分数和加权贡献，展开后展示原题/原回答、五维得分、原文证据、缺口、改进动作与回答框架。

分数状态判断必须使用明确的数字检查：

```javascript
const hasOverallScore = computed(() =>
  report.value?.reportStatus === 'COMPLETE' &&
  Number.isFinite(report.value?.overallScore))
const needsScoring = computed(() =>
  ['PARTIAL', 'UNAVAILABLE'].includes(report.value?.reportStatus))

async function retryScoring() {
  if (loading.value || !sessionId.value) return
  loading.value = true
  error.value = ''
  try { report.value = await api.retryReport(sessionId.value) }
  catch (cause) { error.value = cause.message || '评分重试失败，请稍后再试。' }
  finally { loading.value = false }
}
```

scoreLevel/scoreColor 仅在 hasOverallScore=true 时计算；null 不走 `?? 0` 分支。模板使用 `v-if="hasOverallScore"` 包围总分与等级，其他状态显示“待评分/暂无评分”。保留 printReport，打印样式展开逐题明细并隐藏操作按钮；证据使用 Vue 文本插值，禁止 v-html。移除空数组时“目前回答比较完整”等无证据推断，改为“暂无有效反馈”。页面权重说明明确：出题前锁定相对权重，报告按已答题归一化。

报告首次打开沿用现有 loadReport 调用 GET，重试评分按钮调用 retryScoring；HTTP 读取失败的“重试加载”仍调用 GET。面试结束后的“查看报告”入口沿用已有路由，不把评分网络调用塞进最后一次提交或语音接口。

浏览器 fixture 新增：正常三题加权报告、提前结束、部分评分失败后重试成功、全部失败、无回答、合法 0 分。断言页面不泄露完整评分契约到答题阶段，展开题目可见完整回答与证据，打印包含权重和逐题反馈，语音朗读请求只含 questionId。

- [x] **Step 4：运行 GREEN 和完整验证。**

```sh
cd backend
mvn clean package
cd ../frontend
npm test
npm run build
node tools/check-interview.mjs
```

使用 JDK 17；当前 macOS 可在 Maven 命令前设置 `JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home`。浏览器验收使用既有 Chrome/假设备/模拟 API，不使用真实麦克风或模型密钥。真人真实模型质量另行以若干真实简历人工核对，不能把模拟测试当作评分质量验证。

- [x] **Step 5：更新两处接入说明并自审 diff。** 说明新字段、null 状态、权重计算、缓存与重试、提前结束口径、进程内生命周期；保留上游 skill 原文与 SHA 校验。本次实现保留为工作区变更，提交或 PR 由用户另行指定。

## 验收清单

- [x] 用户结束面试后能在报告页看见每题分数、题目权重和具体反馈。
- [x] 两份项目评分参考文件由 Java 按阶段显式加载；上游 skill 原文及原始 SHA 校验保持不变。
- [x] 每题分类、难度、权重和评分标准在接收该题回答前已保存，后续不可修改。
- [x] Java 从五维分数重算逐题分数，再按冻结权重计算总分；可用测试数据手算复核。
- [x] 已答题权重展示合计 100%，未回答/待评分不会被当成真实 0 分。
- [x] 评分反馈有可回验的原回答证据，按 questionId 对齐，模型不能替换题目或权重。
- [x] 模型不可用或返回坏数据时无虚构分数；报告仍能展示完整已保存问答。
- [x] 刷新不重复评分，显式重试只处理缺失题，既有成功结果保持不变。
- [x] 提前结束显示范围；零回答、零分与未评分三种情况清晰区分。
- [x] 原有文字/语音面试、pendingAnswer、会话归属、热读取 skill、报告导出保持可用。
- [x] 已实施计划中的功能并执行自动化验证；真实模型评分质量仍需人工样本验收。

## 实施与复核记录（2026-10-15）

- 已完成权重数学策略、提问前契约、逐题证据评估、报告聚合与缓存、显式失败重试、报告页面与打印。两份项目参考按阶段直接读取；六个上游文件 SHA 校验保持不变。
- 增加请求级 `AiClient.withOutputBudget`，结构化提问使用 2,400 tokens、批量评分使用 8,192 tokens；原客户端及其他业务默认预算不变。真实 HTTP 回归覆盖五题报告，预算不足的截断响应已先复现再修正。新增文件 `InterviewOutputBudgetTest.java`，并修改 common/ai/AiClient.java、OpenAiCompatibleAiClient.java。
- 报告及逐题 DTO 显式包含 null 分数字段，覆盖全局 NON_NULL 设置；无分数与真实零分保持区分。
- 特殊 Unicode 空白引文的证据校验问题已建立三个失败回归并修复；无引文的正分仍为待评分，零支持的合法零档位允许空证据。
- 数学与参考文件独立复核无重要问题；报告页面复核无重要问题。后端复核提出的输出预算与空白证据问题已修复并通过针对性测试。
- 前端：105 项 node:test 通过，生产构建通过，Chrome 假设备/模拟 API 验收 46 项通过，含权重、加权总分、提前结束、部分失败重试、空报告、真实零分及打印。
- 真实模型行为验收尚未执行；浏览器模拟测试不替代模型判断质量评估。

最终验证：后端 177 项测试通过，0 失败/错误/跳过，`mvn clean package` 成功；整体验收复核无重要问题。前端 105 项测试和构建、Chrome 46 项验收通过。
