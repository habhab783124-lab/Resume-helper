package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import com.shijie.resumehelper.common.ai.AiClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class InterviewPlanAgentTest {
    @Test
    void focusedPlanCarriesServerVerifiedTargetsIntoFrozenThreeQuestionPlan() {
        String[] prompts = new String[2];
        AiClient ai = (system, user) -> { prompts[0] = system; prompts[1] = user; return """
                {"coverage":[{"id":"evidence","label":"结果证据"}],
                 "specialty":{"kind":"NONE","focus":"无","reason":"短练不附加专项题"}}
                """; };
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var target = new InterviewPlan.PracticeTarget("issue-1", "补充结果证据", "说明基线与结果", java.util.List.of("q1"));
        var target2 = new InterviewPlan.PracticeTarget("issue-2", "补齐验证范围", "解释对照组和样本", java.util.List.of("q2"));
        var target3 = new InterviewPlan.PracticeTarget("issue-3", "明确个人贡献", "区分本人决策和团队工作", java.util.List.of("q3"));
        var targets = List.of(target, target2, target3);
        var request = new InterviewPlanService.PlanRequest(source, "工程师", "", InterviewPlan.CareerStage.CAMPUS,
                InterviewPlan.Level.STANDARD, InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.NONE,
                "practice-req", "source-session", targets);
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "简历", "经历正文", "fp");

        var plan = new InterviewAgent().preparePlan(request, resolved, ai);

        assertEquals(3, plan.mainCount());
        assertEquals(3, plan.plannedCount());
        assertEquals(targets, plan.practiceTargets());
        assertTrue(prompts[1].contains("补充结果证据"));
        assertTrue(prompts[1].contains("说明基线与结果"));
        assertTrue(prompts[1].contains("补齐验证范围"));
        assertTrue(prompts[1].contains("明确个人贡献"));
        assertTrue(prompts[0].contains("不得复述或照抄来源回答"));
    }

    @Test
    void parsesOnlyCoverageAndSpecialtyAdviceAndFreezesRequestFields() {
        String[] prompts = new String[2];
        AiClient ai = (system, user) -> {
            prompts[0] = system;
            prompts[1] = user;
            return """
                    {"coverage":[{"id":"project-impact","label":"项目实践"},{"id":"data-analysis","label":"数据分析"}],
                     "specialty":{"kind":"CASE_STUDY","focus":"增长漏斗分析","reason":"目标岗位偏运营"}}
                    """;
        };
        var request = new InterviewPlanService.PlanRequest(
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.RESUME_VERSION, "v1"),
                "数据分析师", "完整 JD", InterviewPlan.CareerStage.CAMPUS, InterviewPlan.Level.STANDARD,
                InterviewPlan.Mode.QUICK, 3, InterviewPlanService.SpecialtyPreference.AUTO, "req-1");
        var resolved = new InterviewSourceResolver.ResolvedSource(request.source(), "保存版", "Java 项目经历", "fp-v1");

        var plan = new InterviewAgent().preparePlan(request, resolved, ai);

        assertEquals("experience-v1", plan.policyVersion());
        assertEquals(3, plan.mainCount());
        assertEquals(4, plan.plannedCount());
        assertEquals(2, plan.followUpBudget());
        assertEquals(InterviewPlan.SpecialtyKind.CASE_STUDY, plan.specialty().kind());
        assertEquals("fp-v1", plan.sourceFingerprint());
        assertEquals(2, plan.coverage().size());
        assertTrue(prompts[0].contains("不得输出题目"));
        assertFalse(prompts[1].contains("fp-v1"));
    }

    @Test
    void plannedOpeningGeneratesOnlyAQuestionAndUsesFrozenPlanChoices() {
        String[] prompts = new String[2];
        AiClient ai = (system, user) -> {
            prompts[0] = system; prompts[1] = user;
            return "{\"kind\":\"MAIN\",\"question\":\"请说明你如何分析增长数据？\","
                    + "\"competency\":\"数据分析\",\"importance\":\"CORE\",\"difficulty\":\"INTERMEDIATE\","
                    + "\"weightReason\":\"岗位要求\",\"criteria\":["
                    + "{\"dimension\":\"CORRECTNESS\",\"expectation\":\"说明方法\"},"
                    + "{\"dimension\":\"COVERAGE\",\"expectation\":\"覆盖关键指标\"},"
                    + "{\"dimension\":\"REASONING\",\"expectation\":\"说明取舍\"},"
                    + "{\"dimension\":\"EVIDENCE\",\"expectation\":\"提供证据\"},"
                    + "{\"dimension\":\"CLARITY\",\"expectation\":\"表达清晰\"}]}";
        };
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 4, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "运营", "运营岗位 JD",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.CASE_STUDY, "增长分析", "运营职责"),
                java.util.List.of(new InterviewPlan.Coverage("funnel", "增长漏斗")), source, "resume.txt", "fp", java.util.List.of());

        var question = new InterviewAgent().preparePlannedOpening("简历正文", plan, ai, 10);

        assertEquals("请说明你如何分析增长数据？", question.text());
        assertTrue(prompts[0].contains("不得重新选择专项类型"));
        assertTrue(prompts[1].contains("增长漏斗"));
        assertFalse(prompts[0].contains("specialty对象"));
    }

    @Test
    void plannedNextQuestionMustUseAConfirmedCoverageId() {
        String[] prompts = new String[2];
        AiClient ai = (system, user) -> {
            prompts[0] = system; prompts[1] = user;
            return "{\"kind\":\"MAIN\",\"coverageId\":\"project-impact\","
                    + "\"question\":\"如何验证项目效果？\",\"competency\":\"项目分析\","
                    + "\"importance\":\"CORE\",\"difficulty\":\"INTERMEDIATE\",\"weightReason\":\"岗位要求\","
                    + "\"criteria\":["
                    + "{\"dimension\":\"CORRECTNESS\",\"expectation\":\"说明方法\"},"
                    + "{\"dimension\":\"COVERAGE\",\"expectation\":\"覆盖关键指标\"},"
                    + "{\"dimension\":\"REASONING\",\"expectation\":\"说明取舍\"},"
                    + "{\"dimension\":\"EVIDENCE\",\"expectation\":\"提供证据\"},"
                    + "{\"dimension\":\"CLARITY\",\"expectation\":\"表达清晰\"}]}";
        };
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""),
                java.util.List.of(new InterviewPlan.Coverage("project-impact", "项目效果")),
                source, "简历", "fp", java.util.List.of());
        var history = java.util.List.of(new InterviewService.Turn("q1", "首题", "回答"));

        var proposal = new InterviewAgent().preparePlannedNext("简历正文", "工程师", history,
                3, 2, false, plan, ai, 10);

        assertEquals("project-impact", proposal.coverageId());
        assertTrue(prompts[1].contains("project-impact"));
        assertTrue(prompts[1].contains("项目效果"));
        AiClient invalid = (system, user) -> ai.chat(system, user).replace("project-impact", "invented-id");
        assertThrows(InterviewException.class, () -> new InterviewAgent().preparePlannedNext(
                "简历正文", "工程师", history, 3, 2, false, plan, invalid, 10));
    }
}
