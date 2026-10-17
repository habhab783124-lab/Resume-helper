package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InterviewDifficultyPlanTest {
    @TempDir Path tempDir;
    @Test
    void mapsConfirmedLevelsToCatalogDifficulties() {
        assertEquals("EASY", InterviewService.algorithmDifficulty(InterviewPlan.Level.FOUNDATION));
        assertEquals("MEDIUM", InterviewService.algorithmDifficulty(InterviewPlan.Level.STANDARD));
        assertEquals("HARD", InterviewService.algorithmDifficulty(InterviewPlan.Level.ADVANCED));
        assertThrows(IllegalArgumentException.class, () -> InterviewService.algorithmDifficulty(null));
    }

    @Test
    void confirmedAlgorithmDifficultyIsPersistedAndIdempotentAcrossRestart() {
        var store = new FileSystemInterviewStore(tempDir, new ObjectMapper().findAndRegisterModules());
        var clock = Clock.systemUTC();
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "简历正文", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 4, 2, 1,
                InterviewPlan.Level.ADVANCED, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.ALGORITHM, "算法", "岗位要求"),
                List.of(new InterviewPlan.Coverage("project-impact", "项目成果")), source, "cv.txt", "fp", List.of());
        var first = new InterviewService(new InterviewAgent(), store, clock).startAsync("alice", resolved, plan, "start-once");
        var firstSnapshot = store.find("alice", first.sessionId()).orElseThrow();
        var selectedId = firstSnapshot.selectedAlgorithm().id();
        assertEquals("HARD", firstSnapshot.selectedAlgorithm().difficulty());

        var restartedService = new InterviewService(new InterviewAgent(), store, clock);
        var restarted = restartedService.startAsync("alice", resolved, plan, "start-once");
        var restoredId = store.find("alice", restarted.sessionId()).orElseThrow().selectedAlgorithm().id();
        assertEquals(first.sessionId(), restarted.sessionId());
        assertEquals(selectedId, restoredId);
        var nextPractice = restartedService.startAsync("alice", resolved, plan, "start-two");
        var nextId = store.find("alice", nextPractice.sessionId()).orElseThrow().selectedAlgorithm().id();
        assertNotEquals(selectedId, nextId, "a new same-source practice must exclude the prior problem");
    }

    @Test
    void exhaustedDifficultyRequiresExplicitRepeatConsent() {
        var store = new FileSystemInterviewStore(tempDir, new ObjectMapper().findAndRegisterModules());
        var service = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "简历正文", "fp");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 4, 2, 1,
                InterviewPlan.Level.ADVANCED, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.ALGORITHM, "算法", "岗位要求"),
                List.of(new InterviewPlan.Coverage("project-impact", "项目成果")), source, "cv.txt", "fp", List.of());
        for (int i = 0; i < 12; i++) service.startAsync("alice", resolved, plan, "hard-" + i);
        assertThrows(IllegalStateException.class, () -> service.startAsync("alice", resolved, plan, "hard-blocked"));
        var consented = service.startAsync("alice", resolved, plan, "hard-repeat", true);
        assertEquals("HARD", store.find("alice", consented.sessionId()).orElseThrow().selectedAlgorithm().difficulty());
    }

    @Test
    void specialtyGenerationRetryKeepsThePreselectedProblemId() {
        var selectedIds = new java.util.ArrayList<String>();
        var agent = new InterviewAgent() {
            @Override public InterviewQuestion preparePlannedOpening(String resume, InterviewPlan plan,
                    AiClient ai, int timeoutSeconds) {
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
            @Override public InterviewQuestion prepareAlgorithm(Hot100Catalog.Problem selected, AiClient ai,
                    int timeoutSeconds) {
                selectedIds.add(selected.id());
                if (selectedIds.size() == 1) throw new InterviewException(502, "temporary",
                        new InterviewFailure(InterviewFailure.Code.NETWORK, true, "temporary", null));
                return InterviewTestFixtures.contract("CORE", "INTERMEDIATE");
            }
        };
        var service = new InterviewService(agent, null, Clock.systemUTC(), new InterviewProperties(), ignored -> {});
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "retry-source");
        var resolved = new InterviewSourceResolver.ResolvedSource(source, "cv.txt", "简历正文", "fp-retry");
        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 1, 2, 0, 0,
                InterviewPlan.Level.STANDARD, InterviewPlan.CareerStage.CAMPUS, "工程师", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.ALGORITHM, "算法", "岗位要求"),
                List.of(new InterviewPlan.Coverage("impact", "项目效果")), source, "cv.txt", "fp-retry", List.of());
        var opened = service.startAsync("alice", resolved, plan, "retry-open");
        var openingGeneration = opened.generation();
        var opening = new InterviewGenerationCoordinator.GenerationClaim("alice", opened.sessionId(),
                openingGeneration.generationId(), openingGeneration.requestId(), openingGeneration.kind(), opened.revision());
        var openedResult = service.generate(opening, (system, user) -> "unused");
        var current = service.commitGenerationSuccess("alice", opened.sessionId(), opening.generationId(),
                opening.expectedRevision(), openedResult);
        var answered = service.saveAnswer("alice", current.sessionId(),
                new InterviewService.Submission(current.currentQuestion().questionId(), "answer-1", "回答"));
        String generationId = service.claimGeneration("alice", answered.sessionId(), "next-1",
                InterviewSnapshot.GenerationKind.NEXT, answered.revision());
        var claim = new InterviewGenerationCoordinator.GenerationClaim("alice", answered.sessionId(), generationId,
                "next-1", InterviewSnapshot.GenerationKind.NEXT, answered.revision());

        var result = service.generate(claim, (system, user) -> "unused");

        assertEquals(2, selectedIds.size());
        assertEquals(selectedIds.get(0), selectedIds.get(1));
        assertEquals("MEDIUM", result.algorithm().difficulty());
    }

    @Test
    void casePromptAndMetadataUseConfirmedLevel() {
        String[] prompts = new String[2];
        AiClient ai = (system, user) -> {
            prompts[0] = system; prompts[1] = user;
            return "{\"question\":\"给定转化数据，请定位瓶颈并设计验证方案。\",\"competency\":\"数据分析\","
                    + "\"importance\":\"CORE\",\"difficulty\":\"BASIC\",\"weightReason\":\"岗位相关\",\"criteria\":["
                    + "{\"dimension\":\"CORRECTNESS\",\"expectation\":\"分析准确\"},"
                    + "{\"dimension\":\"COVERAGE\",\"expectation\":\"覆盖关键指标\"},"
                    + "{\"dimension\":\"REASONING\",\"expectation\":\"说明推理\"},"
                    + "{\"dimension\":\"EVIDENCE\",\"expectation\":\"给出证据\"},"
                    + "{\"dimension\":\"CLARITY\",\"expectation\":\"表达清晰\"}]}";
        };
        var specialty = new InterviewSpecialty(InterviewSpecialty.Kind.CASE_STUDY, "增长分析", "运营岗位职责");
        var question = new InterviewAgent().prepareCase(specialty, "运营", InterviewPlan.Level.ADVANCED, ai, 10);
        assertTrue(prompts[0].contains("进阶任务"));
        assertEquals(InterviewQuestion.Difficulty.ADVANCED, question.difficulty());
    }
}
