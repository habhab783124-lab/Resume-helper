package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

class InterviewSpecialtyTest {
    final ObjectMapper json = new ObjectMapper();
    final InterviewService service = new InterviewService(new InterviewAgent());
    JsonNode tree(Object value) { return json.valueToTree(value); }
    String opening(String kind, String focus, String reason) {
        try {
            var node = (ObjectNode) json.readTree(question("介绍一次具体经历？"));
            node.set("specialty", tree(Map.of("kind", kind, "focus", focus, "reason", reason)));
            return node.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    String withoutPlan() {
        try {
            var node = (ObjectNode) json.readTree(question("第一题？"));
            node.remove("specialty");
            return node.toString();
        } catch (Exception e) { throw new AssertionError(e); }
    }
    AiClient cases() {
        return (sys,p) -> {
            if (sys.contains("当前模式：CASE_STUDY")) {
                assertTrue(sys.contains("优先于常规提问契约"));
                assertTrue(sys.contains("不要求代码"));
                assertTrue(sys.contains("五维"));
                assertTrue(p.contains("用户运营"));
                assertFalse(p.contains("selectedAlgorithm"));
                return question("某产品有1000名新用户，次日留存30%，预算1万元、两周内完成。请设计提升留存的运营实验，说明分群、指标、资源取舍和风险；不得假设新增数据。")
                        .replace("结合本题说明真实实现、依据与局限", "结合给定数据提出运营假设、验证方法和取舍");
            }
            return question("下一主问题？");
        };
    }
    InterviewService.Session startCase() {
        return service.start("a", "m", "计算机本科，Java开发项目；现申请用户运营", "用户运营", 13, (sys,p) -> {
            assertTrue(sys.contains("目标岗位优先")); assertTrue(sys.contains("方向不明确"));
            assertTrue(sys.contains("CASE_STUDY")); assertTrue(p.contains("用户运营")); assertTrue(p.contains("Java"));
            return opening("CASE_STUDY", "用户运营", "目标为用户运营，应考察业务分析而非编程经历");
        });
    }
    InterviewService.Session answer(InterviewService.Session s, AiClient ai) {
        return service.answer("a", s.sessionId(), s.currentQuestion().questionId(), "我的回答", ai);
    }
    @Test void targetRolePlanExposedOnFirstQuestionAndSoftwareStillUsesAlgorithms() {
        var s = startCase();
        assertEquals("CASE_STUDY", tree(s).path("specialty").path("kind").asText());
        assertEquals("用户运营", tree(s).path("specialty").path("focus").asText());
        var developer = service.start("a", "m", "Java开发项目", "Java开发工程师", 13,
                (sys,p) -> opening("ALGORITHM", "Java软件开发", "目标岗位需要编码能力"));
        assertEquals("ALGORITHM", tree(developer).path("specialty").path("kind").asText());
    }
    @Test void absentRoleUsesResumeDutiesAndUnclearDirectionUsesSafeCase() {
        for (String resume : List.of("负责用户分群、活动策划与留存分析", "应届生，无具体经历")) {
            var s = service.start("a", "m", resume, "", 13, (sys,p) -> {
                assertTrue(sys.contains("职责")); assertTrue(sys.contains("方向不明确")); assertTrue(p.contains(resume));
                return opening("CASE_STUDY", "专业分析", "根据简历职责推断；方向不明确时使用专业案例");
            });
            assertEquals("CASE_STUDY", tree(s).path("specialty").path("kind").asText());
        }
    }
    @Test void malformedPlansFailOpeningAndCanRetryWithoutExhaustingSessionQuota() {
        var invalid = List.of(withoutPlan(), opening("OTHER", "运营", "原因"), opening("CASE_STUDY", " ", "原因"),
                opening("CASE_STUDY", "运营", "x".repeat(501)), opening("CASE_STUDY", "x".repeat(201), "原因"));
        for (int i=0; i<24; i++) {
            String response = invalid.get(i % invalid.size());
            var e = assertThrows(InterviewException.class, () -> service.start("a", "m", "简历", "运营", 13, (sys,p) -> response));
            assertEquals(502, e.status());
        }
        assertNotNull(startCase().currentQuestion());
    }
    @Test void fullCaseFlowHasFrozenPlanTwentyPercentAndCachedBatchedReport() {
        var s = startCase(); var plan = tree(s).path("specialty");
        for (int i=0; i<12; i++) s = answer(s, cases());
        var meta = tree(s).path("questionMetadata").path(s.currentQuestion().questionId());
        assertEquals("CASE_STUDY", meta.path("kind").asText());
        assertTrue(meta.path("algorithm").isNull() || meta.path("algorithm").isMissingNode());
        assertEquals(new BigDecimal("20.00"), s.questionMetadata().get(s.currentQuestion().questionId()).weightPercent());
        assertFalse(s.currentQuestion().question().contains("leetcode")); assertEquals(plan, tree(s).path("specialty"));
        s = answer(s, (sys,p) -> { fail("case must terminate"); return ""; });
        assertEquals("FINISHED", s.status());
        var sizes = new ArrayList<Integer>();
        var report = service.report("a", s.sessionId(), (sys,p) -> {
            try {
                var inputs = json.readTree(p).path("inputs"); sizes.add(inputs.size());
                for (var input : inputs) if (input.path("turn").path("question").asText().contains("1000名")) {
                    assertTrue(input.path("contract").path("criteria").toString().contains("运营假设"));
                    assertEquals(5, input.path("contract").path("criteria").size());
                }
            } catch (Exception e) { throw new AssertionError(e); }
            return assessmentsFromPrompt(p, 80);
        });
        assertEquals(List.of(5,5,3), sizes); assertEquals(new BigDecimal("80.0"), report.overallScore()); assertEquals(13, report.answeredCount());
        assertEquals(new BigDecimal("80.00"), report.turnFeedbacks().subList(0,12).stream().map(InterviewAgent.TurnFeedback::weightPercent).reduce(BigDecimal.ZERO,BigDecimal::add));
        assertEquals("CASE_STUDY", tree(report.turnFeedbacks().get(12)).path("kind").asText());
        assertEquals(report, service.report("a", s.sessionId(), (sys,p) -> { fail("cached"); return ""; }));
    }
    @Test void caseFailurePreservesPendingAndOpeningPlanAcrossRetry() {
        var s = startCase(); for (int i=0; i<11; i++) s = answer(s, cases());
        var current = s; var plan = tree(s).path("specialty");
        var e = assertThrows(InterviewException.class, () -> answer(current, (sys,p) -> sys.contains("当前模式：CASE_STUDY") ? "invalid" : question("下一题")));
        assertEquals(502, e.status()); assertEquals("我的回答", service.get("a", s.sessionId()).pendingAnswer());
        assertEquals(plan, tree(service.get("a", s.sessionId())).path("specialty"));
        s = answer(s, cases()); assertEquals(plan, tree(s).path("specialty"));
    }
    @Test void prematureCaseKindRejectedAndEarlyStopPreservesMissingSpecialtyScope() {
        var s = startCase();
        assertThrows(InterviewException.class, () -> answer(s, (sys,p) -> question("下一题").replace("\"question\":", "\"kind\":\"CASE_STUDY\",\"question\":")));
        service.finish("a", s.sessionId());
        var r = service.report("a", s.sessionId(), (sys,p) -> assessmentsFromPrompt(p, 100));
        assertNull(r.overallScore()); assertTrue(r.overallSummary().contains("专项题未作答")); assertTrue(r.overallSummary().contains("20%"));
    }

    @Test void caseUsesFixedReasonAndMixedScoresApplyExactlyTwentyPercent() {
        var s = startCase();
        for (int i = 0; i < 12; i++) s = answer(s, cases());
        assertEquals("专业案例题固定占本轮综合评分20%", s.questionMetadata().get(s.currentQuestion().questionId()).weightReason());
        String caseId = s.currentQuestion().questionId();
        s = answer(s, cases());
        var report = service.report("a", s.sessionId(), (sys, prompt) -> {
            try {
                var assessments = new ArrayList<Map<String, Object>>();
                for (var input : json.readTree(prompt).path("inputs")) {
                    var turn = input.path("turn");
                    String id = turn.path("questionId").asText();
                    assessments.add(assessment(id, turn.path("answer").asText(), id.equals(caseId) ? 100 : 60));
                }
                return json(Map.of("assessments", assessments));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        assertEquals(new BigDecimal("68.0"), report.overallScore());
        assertEquals(0, report.turnFeedbacks().get(12).weightedContribution().compareTo(new BigDecimal("20")));
    }

    @Test void onlyMissingCaseIsRetriedAndLegitimateZeroRemainsScored() {
        var s = startCase();
        for (int i = 0; i < 13; i++) s = answer(s, cases());
        String caseId = s.turns().get(12).questionId();
        var partial = service.report("a", s.sessionId(), (sys, prompt) -> {
            try {
                var assessments = new ArrayList<Map<String, Object>>();
                for (var input : json.readTree(prompt).path("inputs")) {
                    var turn = input.path("turn");
                    String id = turn.path("questionId").asText();
                    if (!id.equals(caseId)) assessments.add(assessment(id, turn.path("answer").asText(), 0));
                }
                return json(Map.of("assessments", assessments));
            } catch (Exception e) { throw new AssertionError(e); }
        });
        assertEquals("PARTIAL", partial.reportStatus());
        assertEquals(12, partial.scoredCount());
        assertNull(partial.overallScore());
        var complete = service.report("a", s.sessionId(), () -> (sys, prompt) -> {
            try {
                var inputs = json.readTree(prompt).path("inputs");
                assertEquals(1, inputs.size());
                assertEquals(caseId, inputs.get(0).path("turn").path("questionId").asText());
            } catch (Exception e) { throw new AssertionError(e); }
            return assessmentsFromPrompt(prompt, 0);
        }, true);
        assertEquals(new BigDecimal("0.0"), complete.overallScore());
        assertEquals(0, complete.turnFeedbacks().get(12).score());
        assertEquals(partial.turnFeedbacks().get(0), complete.turnFeedbacks().get(0));
    }
}
