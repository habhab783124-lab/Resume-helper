package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InterviewPracticeFeedbackTest {
    private static final com.shijie.resumehelper.common.ai.AiClient AI = (system, prompt) -> "unused";
    @TempDir Path dir;

    @Test
    void quickFeedbackClosesTheGroupOnceAndScoresTheRootWithItsAnsweredFollowUps() {
        AtomicReference<InterviewAgent.AssessmentInput> assessed = new AtomicReference<>();
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Proposal preparePlannedNext(String resume, String role, List<InterviewService.Turn> history,
                    int total, int nextMainIndex, boolean allowFollowUp, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return new Proposal(InterviewTestFixtures.contract("CORE", "INTERMEDIATE"), true);
            }
            @Override public Map<String, InterviewAssessment> assessAnswers(String resume, String role,
                    List<AssessmentInput> inputs, List<InterviewService.Turn> history,
                    com.shijie.resumehelper.common.ai.AiClient ai) {
                assessed.set(inputs.get(0));
                return Map.of(inputs.get(0).turn().questionId(), assessment(inputs.get(0).turn().questionId()));
            }
        };
        var service = create(agent, InterviewPlan.Mode.QUICK);
        var root = service.currentQuestion;
        var saved = service.interviews.saveAnswer("owner", service.sessionId,
                new InterviewService.Submission(root, "answer-root", "我用对照组验证效果"));
        var next = service.interviews.claimGeneration("owner", service.sessionId, "follow-request",
                InterviewSnapshot.GenerationKind.NEXT, saved.revision());
        var nextClaim = new InterviewGenerationCoordinator.GenerationClaim("owner", service.sessionId,
                next, "follow-request", InterviewSnapshot.GenerationKind.NEXT, saved.revision());
        saved = service.interviews.commitGenerationSuccess("owner", service.sessionId, next,
                nextClaim.expectedRevision(), service.interviews.generate(nextClaim, AI));
        String follow = saved.currentQuestion().questionId();
        saved = service.interviews.saveAnswer("owner", service.sessionId,
                new InterviewService.Submission(follow, "answer-follow", "样本和周期保持一致"));

        var queued = service.interviews.queuePracticeFeedback("owner", service.sessionId, follow,
                "feedback-1", saved.revision());
        assertTrue(queued.enqueue());
        assertTrue(queued.session().closedRootIds().contains(root));
        assertEquals(InterviewPracticeFeedback.Status.QUEUED, queued.session().practiceFeedbacks().get(root).status());
        assertFalse(service.interviews.queuePracticeFeedback("owner", service.sessionId, root,
                "feedback-1", queued.session().revision()).enqueue());
        assertTrue(service.interviews.startPracticeFeedback("owner", service.sessionId, root, "feedback-1"));
        var context = service.interviews.practiceContext("owner", service.sessionId, root, "feedback-1");
        assertTrue(context.input().followUps().stream().anyMatch(turn -> turn.questionId().equals(follow)));
        var result = agent.assessAnswers(context.resume(), context.role(), List.of(context.input()), context.history(), AI);
        service.interviews.completePracticeFeedback("owner", service.sessionId, root, "feedback-1", result.get(root));
        assertEquals(InterviewPracticeFeedback.Status.COMPLETE,
                service.interviews.get("owner", service.sessionId).practiceFeedbacks().get(root).status());
        assertEquals("我用对照组验证效果", assessed.get().turn().answer());
    }

    @Test
    void fullActiveSessionsCannotRevealTeachingFeedbackAndFailuresDoNotReopenTheGroup() {
        var service = create(new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        }, InterviewPlan.Mode.FULL);
        String root = service.currentQuestion;
        var session = service.interviews.saveAnswer("owner", service.sessionId,
                new InterviewService.Submission(root, "answer", "真实回答"));
        assertThrows(InterviewException.class, () -> service.interviews.queuePracticeFeedback("owner", service.sessionId,
                root, "full-feedback", session.revision()));

        var quick = create(new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        }, InterviewPlan.Mode.QUICK);
        var saved = quick.interviews.saveAnswer("owner", quick.sessionId,
                new InterviewService.Submission(quick.currentQuestion, "answer", "真实回答"));
        var task = quick.interviews.queuePracticeFeedback("owner", quick.sessionId, quick.currentQuestion,
                "failed-feedback", saved.revision());
        assertTrue(quick.interviews.startPracticeFeedback("owner", quick.sessionId, quick.currentQuestion, "failed-feedback"));
        quick.interviews.failPracticeFeedback("owner", quick.sessionId, quick.currentQuestion, "failed-feedback", "模型不可用");
        var afterFailure = quick.interviews.get("owner", quick.sessionId);
        assertEquals(InterviewPracticeFeedback.Status.FAILED, afterFailure.practiceFeedbacks().get(quick.currentQuestion).status());
        assertTrue(afterFailure.closedRootIds().contains(quick.currentQuestion));
        assertNull(afterFailure.currentQuestion());
        assertThrows(InterviewException.class, () -> quick.interviews.queuePracticeFeedback("owner", quick.sessionId,
                quick.currentQuestion, "another-request", afterFailure.revision()));
    }

    @Test
    void asynchronousFailureIsVisibleButLeavesTheQuickSessionReadyForTheNextQuestion() throws Exception {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public Map<String, InterviewAssessment> assessAnswers(String resume, String role,
                    List<AssessmentInput> inputs, List<InterviewService.Turn> history,
                    com.shijie.resumehelper.common.ai.AiClient ai) {
                throw new RuntimeException("model failed");
            }
        };
        var fixture = create(agent, InterviewPlan.Mode.QUICK);
        var saved = fixture.interviews.saveAnswer("owner", fixture.sessionId,
                new InterviewService.Submission(fixture.currentQuestion, "answer", "真实回答"));
        var practice = new InterviewPracticeService(fixture.interviews, agent);
        try {
            var queued = practice.feedback("owner", fixture.sessionId, fixture.currentQuestion, "async-failure",
                    saved.revision(), AI);
            assertTrue(List.of(InterviewPracticeFeedback.Status.QUEUED, InterviewPracticeFeedback.Status.RUNNING,
                    InterviewPracticeFeedback.Status.FAILED).contains(queued.practiceFeedbacks().get(fixture.currentQuestion).status()));
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2);
            InterviewService.Session latest = queued;
            while (System.nanoTime() < deadline) {
                latest = fixture.interviews.get("owner", fixture.sessionId);
                if (latest.practiceFeedbacks().get(fixture.currentQuestion).status() == InterviewPracticeFeedback.Status.FAILED) break;
                Thread.sleep(10);
            }
            assertEquals(InterviewPracticeFeedback.Status.FAILED,
                    latest.practiceFeedbacks().get(fixture.currentQuestion).status());
            assertTrue(latest.closedRootIds().contains(fixture.currentQuestion));
            assertNull(latest.currentQuestion());
        } finally { practice.close(); }
    }

    @Test
    void interruptedQuickFeedbackRestoresAsRetryableFailureWithoutReopeningTheGroup() {
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    com.shijie.resumehelper.common.ai.AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var mapper = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        InterviewStore store = new FileSystemInterviewStore(dir, mapper);
        var first = create(agent, InterviewPlan.Mode.QUICK, store);
        var saved = first.interviews.saveAnswer("owner", first.sessionId,
                new InterviewService.Submission(first.currentQuestion, "answer", "真实回答"));
        var queued = first.interviews.queuePracticeFeedback("owner", first.sessionId, first.currentQuestion,
                "persisted-feedback", saved.revision());

        var restarted = new InterviewService(agent, store, Clock.systemUTC()).get("owner", first.sessionId);
        assertEquals(InterviewPracticeFeedback.Status.FAILED,
                restarted.practiceFeedbacks().get(first.currentQuestion).status());
        assertTrue(restarted.practiceFeedbacks().get(first.currentQuestion).error().contains("重启"));
        assertTrue(restarted.closedRootIds().contains(first.currentQuestion));
        assertEquals(queued.session().revision(), restarted.revision());
    }

    private static InterviewAssessment assessment(String id) {
        var dimensions = new EnumMap<InterviewQuestion.Dimension, Integer>(InterviewQuestion.Dimension.class);
        for (var dimension : InterviewQuestion.Dimension.values()) dimensions.put(dimension, 10);
        return new InterviewAssessment(id, dimensions, List.of("我用对照组验证效果"), List.of("补充统计周期"),
                "说明了验证方法", "补充真实统计周期", "指标、对照组、结果", "test-model");
    }

    private static Fixture create(InterviewAgent agent, InterviewPlan.Mode mode) {
        return create(agent, mode, null);
    }

    private static Fixture create(InterviewAgent agent, InterviewPlan.Mode mode, InterviewStore store) {
        int mainCount = mode == InterviewPlan.Mode.QUICK ? 3 : 12;
        var plan = new InterviewPlan("experience-v1", mode, mainCount, mode == InterviewPlan.Mode.QUICK ? 3 : 13,
                2, 1, InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(mode == InterviewPlan.Mode.QUICK ? InterviewPlan.SpecialtyKind.NONE : InterviewPlan.SpecialtyKind.ALGORITHM,
                        mode == InterviewPlan.Mode.QUICK ? "" : "算法", "专项题用于核对岗位相关能力"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")),
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1"), "简历", "fp", List.of());
        var source = new InterviewSourceResolver.ResolvedSource(plan.source(), "简历", "正文", "fp");
        var interviews = new InterviewService(agent, store, Clock.systemUTC());
        var session = interviews.startAsync("owner", source, plan, "start-" + mode);
        String generationId = interviews.claimGeneration("owner", session.sessionId(), "opening-" + mode,
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        var claim = new InterviewGenerationCoordinator.GenerationClaim("owner", session.sessionId(), generationId,
                "opening-" + mode, InterviewSnapshot.GenerationKind.OPENING, session.revision());
        var opened = interviews.commitGenerationSuccess("owner", session.sessionId(), claim.generationId(),
                claim.expectedRevision(), interviews.generate(claim, AI));
        return new Fixture(interviews, opened.sessionId(), opened.currentQuestion().questionId());
    }

    private record Fixture(InterviewService interviews, String sessionId, String currentQuestion) {}
}
