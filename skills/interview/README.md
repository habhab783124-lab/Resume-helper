# 面试 Agent 的 interview skill

面试 Agent 直接读取本目录的 `SKILL.md` 和 `references/`，每次出题、生成报告前读取最新内容。修改文件后，下一次请求即可生效。

来源为 [Hisn00w/ASu-skills](https://github.com/Hisn00w/ASu-skills/tree/main/skills/interview)。原始 skill 文件保持不变，固定提交与校验和见 `UPSTREAM.md`，许可证见 `LICENSE`。项目适配逻辑位于 `backend/src/main/java/com/shijie/resumehelper/interview/InterviewSkill.java`，由 `InterviewAgent` 注入实际模型请求的系统提示词。

## 目录与启动

默认从项目根目录的 `skills/interview/` 读取，支持从项目根目录或 `backend/` 启动。此目录是唯一的 skill 来源，后端 resources 和 jar 中不保留重复副本。文件缺失、为空或无法读取时明确报错。

从其他目录启动或单独部署 jar 时，需要同时部署本目录，并指定路径：

```sh
INTERVIEW_SKILL_DIR=/path/to/skills/interview java -jar backend/target/resume-helper-backend-0.1.0-SNAPSHOT.jar
```

也可使用 JVM 参数 `-Dinterview.skill.dir=/path/to/skills/interview`，优先于环境变量。相对路径按进程工作目录解析，显式配置后只使用指定目录。

首次使用这次 Java 代码变更需重新启动后端；以后只修改 skill 文件无需重启或重新打包。

## 接入范围

- **Grill 出题**：读取主文档、面试契约、问题与评分契约、场景题参考，围绕真实简历 Claim 追问，一次只输出一道题，结束后统一反馈。
- **Review 报告**：读取主文档、问题与评分契约、复盘与弱项复练参考，将证据、缺口和建议映射到原有 JSON 报告字段。
- 题数预算沿用现有 1–10 题。简历、岗位和回答作为不可信候选人数据处理，连通性检查保持短提示词。

为兼容现有报告界面保留整数分数，并要求标注“训练参考评分”和样本范围。五维量表为切题与正确性、行动与结果证据、个人职责边界、实现与取舍深度、前后一致性，各取 0/10/20 分；总体分数取已回答题目的平均值。量表由模型按提示词计算，没有外部校准，服务端不独立重算五维分数。

当前没有独立的 Predict、Retry 入口、跨会话复练队列、持久化 Claim 账本或提问前锁定的评分契约。Agent 每次从已有历史重建最小证据状态；复练以报告建议呈现，skill 中提到的其他 skills 和文件工具不会执行。

## 验证与更新

使用 JDK 17，在 `backend/` 执行：

```sh
mvn -Dtest=InterviewSkillFileLoadingTest,InterviewSkillIntegrationTest,InterviewModelTransportTest,InterviewFlowTest,InterviewReportApiTest test
mvn clean package
```

文件加载测试验证出题和报告使用指定目录、修改后生效、缺失或空文件报错；集成和传输测试验证模型收到 skill、简历与历史，以及原有单题和 JSON 协议。测试不使用真实 API Key。

更新上游版本时先下载到临时目录，审阅后替换本目录文件，更新 `UPSTREAM.md` 中的提交和校验和，保留许可证并运行测试。
