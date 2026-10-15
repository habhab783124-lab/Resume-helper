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
    }

    @AfterEach void restoreDirectory() {
        if (previousDirectory == null) System.clearProperty("interview.skill.dir");
        else System.setProperty("interview.skill.dir", previousDirectory);
    }

    @Test void questionAndReportReadTheConfiguredProjectFiles() {
        var systems = new ArrayList<String>();
        AiClient ai = (system, prompt) -> {
            systems.add(system);
            return system.contains("当前模式：Review") ? "{\"overallScore\":60}" : "请介绍个人职责？";
        };
        var agent = new InterviewAgent();
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);
        agent.generateReport("简历", "后端",
                List.of(new InterviewService.Turn("q1", "个人职责？", "实现缓存")), ai);

        assertEquals(2, systems.size());
        for (String system : systems) {
            assertTrue(system.contains("local-skill-version-one"));
            assertTrue(system.contains("local-scoring-contract"));
        }
        assertTrue(systems.get(0).contains("local-session-contract"));
        assertTrue(systems.get(0).contains("local-scenario-contract"));
        assertTrue(systems.get(1).contains("local-review-contract"));
        assertFalse(systems.get(1).contains("local-scenario-contract"));
    }

    @Test void editsAreReadOnTheNextRequestWithoutRestartingTheAgent() throws Exception {
        var systems = new ArrayList<String>();
        AiClient ai = (system, prompt) -> { systems.add(system); return "请介绍个人职责？"; };
        var agent = new InterviewAgent();
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);
        write("SKILL.md", "local-skill-version-two");
        write("references/question-and-scoring-contract.md", "updated-scoring-contract");
        agent.nextQuestion("简历", "后端", List.of(), 3, ai);

        assertTrue(systems.get(0).contains("local-skill-version-one"));
        assertTrue(systems.get(1).contains("local-skill-version-two"));
        assertTrue(systems.get(1).contains("updated-scoring-contract"));
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
        var error = assertThrows(IllegalStateException.class, () -> new InterviewAgent().generateReport(
                "简历", "后端", List.of(new InterviewService.Turn("q1", "个人职责？", "实现缓存")),
                (system, prompt) -> "{\"overallScore\":60}"));
        assertTrue(error.getMessage().contains("review-and-retry.md"));
    }

    private void write(String relativePath, String content) throws Exception {
        Path file = directory.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }
}
