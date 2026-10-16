package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.*;
import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

class InterviewTwelvePlusOneTest {
    final ObjectMapper json = new ObjectMapper();
    final InterviewService service = new InterviewService(new InterviewAgent());

    JsonNode tree(Object value) {
        return json.valueToTree(value);
    }

    AiClient questions() {
        return (s, p) -> {
            if (p.contains("selectedAlgorithm")) {
                try {
                    return json(Map.of("algorithmId", json.readTree(p).path("selectedAlgorithm").path("id").asText(),
                             "description", "给定一组输入，求满足目标的结果。", "examples", "输入 [1,2]，输出 [0,1]。", "constraints",
                             "输入长度1至10000，考虑空值及边界。"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            return question("说明你的实现和取舍？");
        };
    }

    InterviewService.Session start() {
        return service.start("a", "m", "Java Redis 项目", "Java", 1, questions());
    }

    InterviewService.Session answer(InterviewService.Session s, String value, AiClient ai) {
        return service.answer("a", s.sessionId(), s.currentQuestion().questionId(), value, ai);
    }

    @Test
    void fixedThirteenRootsWithAlgorithmLast() {
        var s = start();
        assertEquals(13, s.questionCount());
        for (int i = 1; i <= 13; i++) {
            var m = tree(s).path("questionMetadata").path(s.currentQuestion().questionId());
            assertEquals(i, m.path("mainQuestionIndex").asInt());
            assertEquals(i == 13?"ALGORITHM":"MAIN", m.path("kind").asText());
            if (i == 13) {
                assertTrue(m.path("algorithm").path("url").asText().startsWith("https://leetcode.cn/problems/"));
                assertTrue(s.currentQuestion().question().contains("复杂度"));
            }
            s = answer(s, "我的回答", questions());
        }
        assertEquals("FINISHED", s.status());
        assertEquals(13, s.turns().size());
        assertNull(s.currentQuestion());
    }

    @Test
    void bundledCatalogHasOfficialHundredUniqueEntries() throws Exception {
        try (var in = getClass().getResourceAsStream("/interview/hot100.json")) {
            assertNotNull(in, "official catalog must be bundled");
            var c = json.readTree(in);
            assertEquals("https://leetcode.cn/studyplan/top-100-liked/", c.path("sourceUrl").asText());
            var ids = new HashSet<String>();
            var slugs = new HashSet<String>();
            for (var q : c.path("problems")) {
                assertTrue(ids.add(q.path("id").asText()));
                assertTrue(slugs.add(q.path("slug").asText()));
                assertFalse(q.path("title").asText().isBlank());
                assertTrue(Set.of("EASY", "MEDIUM", "HARD").contains(q.path("difficulty").asText()));
            }
            assertEquals(100, ids.size());
            assertTrue(ids.containsAll(Set.of("1", "49", "128", "283", "146", "994", "23")));
        }
    }

    @Test
    void followupsHaveServerParentsCapTwoAndNoRootWeight() {
        var s = start();
        String root = s.currentQuestion().questionId();
        String follow = question("补充亲自负责的实现？").replace("\"question\":", "\"kind\":\"FOLLOW_UP\",\"parentQuestionId\":\"forged\",\"question\":");
        for (int i = 0; i < 2; i++) {
            s = answer(s, "我负责设计", (sys, p) -> follow);
            var m = tree(s).path("questionMetadata").path(s.currentQuestion().questionId());
            assertEquals("FOLLOW_UP", m.path("kind").asText());
            assertEquals(root, m.path("parentQuestionId").asText());
            assertEquals(1, m.path("mainQuestionIndex").asInt());
            assertTrue(m.path("weightPercent").isNull());
        }
        s = answer(s, "补充证据", questions());
        assertEquals(2, tree(s).path("questionMetadata").path(s.currentQuestion().questionId()).path("mainQuestionIndex").asInt());
        service.finish("a", s.sessionId());
        var r = service.report("a", s.sessionId(), (sys, p) -> assessmentsFromPrompt(p, 80));
        assertEquals(1, r.answeredCount());
        assertEquals(2, r.turnFeedbacks().size());
        assertEquals(2, tree(r).path("turnFeedbacks").get(0).path("followUps").size());
        assertNull(r.overallScore());
    }

    @Test
    void algorithmRetryKeepsFrozenSelectionAndPendingAnswer() {
        var s = start();
        for (int i = 0; i < 11; i++)s = answer(s, "我的回答", questions());
        var ids = new ArrayList<String>();
        AiClient bad = (sys, p) -> {
            if (p.contains("selectedAlgorithm")) {
                try {
                    ids.add(json.readTree(p).path("selectedAlgorithm").path("id").asText());
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
                return "invalid";
            }
            return question("下一题");
        };
        final var current = s;
        assertThrows(InterviewException.class, () -> answer(current, "第十二题回答", bad));
        assertThrows(InterviewException.class, () -> answer(current, "第十二题回答", bad));
        assertEquals(2, ids.size());
        assertEquals(ids.get(0), ids.get(1));
        assertEquals("第十二题回答", service.get("a", s.sessionId()).pendingAnswer());
        s = answer(s, "第十二题回答", questions());
        assertEquals(ids.get(0), tree(s).path("questionMetadata").path(s.currentQuestion().questionId()).path("algorithm").path("id").asText());
    }

    @Test
    void reportBatchesFiveAndRetriesOnlyFailedGroups() {
        var s = start();
        for (int i = 0; i < 13; i++)s = answer(s, "我的回答", questions());
        var calls = new AtomicInteger();
        var sizes = new ArrayList<Integer>();
        AiClient flaky = (sys, p) -> {
            try {
                sizes.add(json.readTree(p).path("inputs").size());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            if (calls.incrementAndGet() == 2)throw new AiClient.AiException("timeout");
            return assessmentsFromPrompt(p, 80);
        };
        var r = service.report("a", s.sessionId(), flaky);
        assertEquals(List.of(5, 5, 3), sizes);
        assertEquals(8, r.scoredCount());
        assertNull(r.overallScore());
        var retrySizes = new ArrayList<Integer>();
        r = service.report("a", s.sessionId(), () -> (sys, p) -> {
            try {
                retrySizes.add(json.readTree(p).path("inputs").size());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return assessmentsFromPrompt(p, 80);
        }, true);
        assertEquals(List.of(5), retrySizes);
        assertEquals(13, r.scoredCount());
        assertEquals(new BigDecimal("80.0"), r.overallScore());
        assertEquals(new BigDecimal("80.00"), r.turnFeedbacks().subList(0, 12).stream().map(InterviewAgent.TurnFeedback::weightPercent).reduce(BigDecimal.ZERO,
                 BigDecimal::add));
        assertEquals(new BigDecimal("20.00"), r.turnFeedbacks().get(12).weightPercent());
    }

    @Test
    void earlyStopHasNoOverallAndIncludesUnansweredWeightAndAlgorithmAbsence() {
        var s = start();
        s = answer(s, "我的回答", questions());
        service.finish("a", s.sessionId());
        var r = service.report("a", s.sessionId(), (sys, p) -> assessmentsFromPrompt(p, 100));
        assertNull(r.overallScore());
        assertEquals(13, r.plannedCount());
        assertTrue(r.overallSummary().contains("专项题"));
        assertTrue(r.overallSummary().contains("主问题"));
        assertEquals(new BigDecimal("80.00"), r.turnFeedbacks().stream().map(InterviewAgent.TurnFeedback::weightPercent).reduce(BigDecimal.ZERO,
                 BigDecimal::add));
        assertEquals("UNANSWERED", r.turnFeedbacks().get(1).status());
    }

    @Test
    void followUpRetryKeepsParentRubricPendingAnswerAndIdempotency() throws Exception {
        var s = start();
        String parent = s.currentQuestion().questionId();
        String follow = question("补充实现细节？", "SUPPLEMENT", "BASIC") .replace("\"question\":", "\"kind\":\"FOLLOW_UP\",\"question\":") .replace("结合本题说明真实实现、依据与局限",
                 "不应生效的追问标准");
        s = answer(s, "主回答", (sys, p) -> follow);
        final var first = s;
        assertThrows(InterviewException.class, () -> answer(first, "追问回答", (sys, p) -> "invalid"));
        assertEquals("追问回答", service.get("a", s.sessionId()).pendingAnswer());
        assertThrows(InterviewException.class, () -> answer(first, "替换追问回答", questions()));
        s = answer(s, "追问回答", (sys, p) -> follow);
        String secondFollowId = s.currentQuestion().questionId();
        assertEquals(s, service.answer("a", s.sessionId(), first.currentQuestion().questionId(), "追问回答", (sys, p) -> {
            fail("duplicate must not call AI");
            return "";
        }));
        final var capped = s;
        assertThrows(InterviewException.class, () -> answer(capped, "第二次追问回答", (sys, p) -> follow));
        assertEquals(secondFollowId, service.get("a", s.sessionId()).currentQuestion().questionId());
        s = answer(s, "第二次追问回答", questions());
        assertEquals(2, tree(s).path("questionMetadata").path(s.currentQuestion().questionId()).path("mainQuestionIndex").asInt());
        service.finish("a", s.sessionId());
        service.report("a", s.sessionId(), (sys, p) -> {
            try {
                var input = json.readTree(p).path("inputs").get(0);
                assertEquals(parent, input.path("turn").path("questionId").asText());
                assertEquals(2, input.path("followUps").size());
                assertEquals("CORE", input.path("contract").path("importance").asText());
                assertFalse(input.path("contract").toString().contains("不应生效"));
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            return assessmentsFromPrompt(p, 80);
        });
    }

    @Test
    void algorithmAuthenticationErrorsKeepConfigurationStatusAndFrozenSelection() {
        var selected = Hot100Catalog.problems().get(0);
        var error = assertThrows(InterviewException.class, () -> new InterviewAgent().prepareAlgorithm(selected, (sys, p) -> {
            throw new AiClient.AiException("unauthorized", false);
        }));
        assertEquals(400, error.status());
    }

    @Test
    void algorithmGenerationHasDedicatedSystemResponseProtocol() {
        var selected = Hot100Catalog.problems().get(0);
        new InterviewAgent().prepareAlgorithm(selected, (sys, p) -> {
            assertTrue(sys.contains("当前模式：ALGORITHM"));
            assertTrue(sys.contains("优先于常规提问契约"));
            for (String field : List.of("algorithmId", "description", "examples", "constraints")) assertTrue(sys.contains(field));
            assertTrue(sys.contains("不得泄露解法"));
            return algorithmFromPrompt(p);
        });
    }

    @Test
    void nextQuestionRejectsUnknownAndPrematureAlgorithmKinds() {
        for (String kind : List.of("INVALID", "ALGORITHM", "CASE_STUDY", "SPECIALTY")) {
            var session = start();
            String invalid = question("下一题").replace("\"question\":", "\"kind\":\""+kind+"\",\"question\":");
            assertThrows(InterviewException.class, () -> answer(session, "保留原回答", (sys, p) -> invalid));
            var failed = service.get("a", session.sessionId());
            assertEquals(session.currentQuestion().questionId(), failed.currentQuestion().questionId());
            assertEquals("保留原回答", failed.pendingAnswer());
        }
    }

    @Test
    void allMainFollowUpsRemainOutsideQuotaAndAlgorithmNeverCallsNextQuestion() {
        var s = start();
        String follow = question("补充说明？").replace("\"question\":", "\"kind\":\"FOLLOW_UP\",\"question\":");
        for (int index = 1; index <= 12; index++) {
            String parent = s.currentQuestion().questionId();
            assertEquals(index, s.questionMetadata().get(parent).mainQuestionIndex());
            for (int j = 0; j < 2; j++) {
                s = answer(s, "主回答或追问回答", (sys, p) -> follow);
                assertEquals(parent, s.questionMetadata().get(s.currentQuestion().questionId()).parentQuestionId());
            }
            s = answer(s, "第二次追问回答", questions());
        }
        assertEquals(InterviewService.Kind.ALGORITHM, s.questionMetadata().get(s.currentQuestion().questionId()).kind());
        s = answer(s, "算法思路与伪代码", (sys, p) -> {
            fail("algorithm is terminal");
            return "";
        });
        assertEquals("FINISHED", s.status());
        assertEquals(37, s.turns().size());
        var report = service.report("a", s.sessionId(), (sys, p) -> assessmentsFromPrompt(p, 80));
        assertEquals(13, report.answeredCount());
        assertEquals(13, report.scoredCount());
        assertEquals(13, report.turnFeedbacks().size());
        for (int i = 0; i < 12; i++) assertEquals(2, report.turnFeedbacks().get(i).followUps().size());
        assertTrue(report.turnFeedbacks().get(12).followUps().isEmpty());
        assertEquals(new BigDecimal("80.0"), report.overallScore());
    }
}
