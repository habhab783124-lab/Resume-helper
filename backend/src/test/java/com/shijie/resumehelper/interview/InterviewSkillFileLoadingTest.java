package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.parallel.Resources.SYSTEM_PROPERTIES;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

@ResourceLock(SYSTEM_PROPERTIES)
class InterviewSkillFileLoadingTest {
    @TempDir Path directory;
    private String previousDirectory;

    @BeforeEach void setUp() throws Exception {
        previousDirectory = System.getProperty("interview.skill.dir");
        System.setProperty("interview.skill.dir", directory.toString());
        write("SKILL.md", "local-skill-version-one");
        write("references/interview-contract-and-session.md", "local-session-contract");
        write("references/question-and-scoring-contract.md", "local-scoring-contract");
        write("references/scenario-interviews.md", "local-scenario-contract");
        write("references/review-and-retry.md", "local-review-contract");
        write("references/project-question-contract.md", "local-project-question");
        write("references/project-scoring-and-feedback.md", "local-project-feedback");
    }

    @AfterEach void restoreDirectory() {
        if (previousDirectory == null) System.clearProperty("interview.skill.dir");
        else System.setProperty("interview.skill.dir", previousDirectory);
    }

    @Test void questionAndReportReadTheConfiguredProjectFiles() {
        var systems = new ArrayList<String>();
        AiClient ai = (system, prompt) -> {
            systems.add(system);
            return system.contains("当前模式：Review") ? assessmentsFromPrompt(prompt, 60) : question("请介绍个人职责？");
        };
        var agent = new InterviewAgent();
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);
        agent.assessAnswers("简历", "后端", List.of(new InterviewAgent.AssessmentInput(
                new InterviewService.Turn("q1", "个人职责？", "实现缓存"), contract("CORE", "BASIC"))), List.of(), ai);

        assertEquals(2, systems.size());
        for (String system : systems) {
            assertTrue(system.contains("local-skill-version-one"));
            assertTrue(system.contains("local-scoring-contract"));
        }
        assertTrue(systems.get(0).contains("local-session-contract"));
        assertTrue(systems.get(0).contains("local-scenario-contract"));
        assertTrue(systems.get(1).contains("local-review-contract"));
        assertFalse(systems.get(1).contains("local-scenario-contract"));
        assertTrue(systems.get(0).contains("local-project-question"));
        assertFalse(systems.get(0).contains("local-project-feedback"));
        assertTrue(systems.get(1).contains("local-project-feedback"));
        assertFalse(systems.get(1).contains("local-project-question"));
    }

    @Test void editsAreReadOnTheNextRequestWithoutRestartingTheAgent() throws Exception {
        var systems = new ArrayList<String>();
        AiClient ai = (system, prompt) -> { systems.add(system); return question("请介绍个人职责？"); };
        var agent = new InterviewAgent();
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);
        write("SKILL.md", "local-skill-version-two");
        write("references/question-and-scoring-contract.md", "updated-scoring-contract");
        write("references/project-question-contract.md", "updated-project-question");
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);

        assertTrue(systems.get(0).contains("local-skill-version-one"));
        assertTrue(systems.get(1).contains("local-skill-version-two"));
        assertTrue(systems.get(1).contains("updated-scoring-contract"));
        assertTrue(systems.get(1).contains("updated-project-question"));
        assertFalse(systems.get(1).contains("local-skill-version-one"));
    }

    @Test void missingFileFailsBeforeCallingTheModelInsteadOfUsingABundledCopy() throws Exception {
        Files.delete(directory.resolve("references/question-and-scoring-contract.md"));
        var calls = new AtomicInteger();
        var error = assertThrows(IllegalStateException.class, () -> new InterviewAgent().nextQuestion(
                "简历", "后端", List.of(), 3, (system, prompt) -> { calls.incrementAndGet(); return "题目"; }));

        assertTrue(error.getMessage().contains("question-and-scoring-contract.md"));
        assertEquals(0, calls.get());
    }

    @Test void emptySkillIsReportedAsAConfigurationError() throws Exception {
        write("SKILL.md", "   \n");
        var error = assertThrows(IllegalStateException.class, () -> new InterviewAgent().nextQuestion(
                "简历", "后端", List.of(), 3, (system, prompt) -> "题目"));
        assertTrue(error.getMessage().contains("为空"));
    }

    @Test void missingReviewFileIsReportedInsteadOfBecomingARuleReport() throws Exception {
        Files.delete(directory.resolve("references/review-and-retry.md"));
        var error = assertThrows(IllegalStateException.class, () -> new InterviewAgent().assessAnswers(
                "简历", "后端", List.of(new InterviewAgent.AssessmentInput(
                new InterviewService.Turn("q1", "个人职责？", "实现缓存"), contract("CORE", "BASIC"))), List.of(),
                (system, prompt) -> "{}"));
        assertTrue(error.getMessage().contains("review-and-retry.md"));
    }

    @Test void skillEditsDoNotChangeFrozenCriteriaOrCachedAssessments() throws Exception {
        var service = new InterviewService(new InterviewAgent());
        var session = service.start("owner", "mat", "简历", "后端", 1, (system, prompt) ->
                question("缓存失效怎么办？").replace("结合本题说明真实实现、依据与局限", "提问时锁定的通过标准"));
        service.answer("owner", session.sessionId(), session.currentQuestion().questionId(), "我实现缓存", null);
        write("references/project-scoring-and-feedback.md", "updated-project-feedback");
        var report = service.report("owner", session.sessionId(), (system, prompt) -> {
            assertTrue(system.contains("updated-project-feedback"));
            assertTrue(prompt.contains("提问时锁定的通过标准"));
            return assessmentsFromPrompt(prompt, 80);
        });
        Files.delete(directory.resolve("references/project-scoring-and-feedback.md"));
        assertEquals(report, service.report("owner", session.sessionId(), (system, prompt) -> {
            fail("成功的报告不应因 skill 热更新再次评分"); return "";
        }));
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
