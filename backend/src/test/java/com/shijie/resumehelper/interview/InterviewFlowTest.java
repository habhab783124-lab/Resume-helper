package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.job.ai.AiClient;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class InterviewFlowTest {
    private final MaterialService materials = new MaterialService();
    private final InterviewService service = new InterviewService(materials, new InterviewAgent());
    private String upload(String owner) {
        return materials.upload(owner, new MockMultipartFile("file", "resume.txt", "text/plain",
                "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。".getBytes(StandardCharsets.UTF_8))).materialId();
    }
    @Test void fullFlowIncludesResumeAndHistoryAndStopsAtLimit() {
        var calls = new AtomicInteger();
        AiClient ai = (system, prompt) -> {
            assertTrue(prompt.contains("Redis"));
            if (calls.incrementAndGet() > 1) assertTrue(prompt.contains("缓存命中率"));
            return "请说明你如何验证缓存效果？";
        };
        var session = service.start("a", upload("a"), "Java 后端", 2, ai);
        assertEquals("ACTIVE", session.status());
        var next = service.answer("a", session.sessionId(), session.currentQuestion().questionId(), "缓存命中率达到90%", ai);
        assertEquals(2, next.turns().size());
        var end = service.answer("a", session.sessionId(), next.currentQuestion().questionId(), "通过监控验证", ai);
        assertEquals("FINISHED", end.status());
        assertNull(end.currentQuestion());
        assertEquals(2, calls.get());
    }
    @Test void failedGenerationPreservesAnswerAndRetriesWithoutSkipping() {
        var session = service.start("a", upload("a"), "", 3, (s,p)->"介绍你的项目？");
        String question = session.currentQuestion().questionId();
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "我的回答", (s,p)->{throw new AiClient.AiException("secret upstream error");}));
        var failed = service.get("a", session.sessionId());
        assertEquals("我的回答", failed.pendingAnswer());
        assertEquals(question, failed.currentQuestion().questionId());
        assertEquals(1, failed.turns().size());
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "替换回答", (s,p)->"不应调用"));
        assertEquals("我的回答", service.get("a", session.sessionId()).pendingAnswer());
        var success = service.answer("a", session.sessionId(), question, "我的回答", (s,p)->"遇到了什么困难？");
        assertNull(success.pendingAnswer());
        assertEquals(2, success.turns().size());
        assertEquals(success, service.answer("a", session.sessionId(), question, "我的回答", (s,p)->{fail("duplicate invokes AI");return "";}));
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "不同回答", (s,p)->"x"));
    }
    @Test void cannotAccessAnotherBrowserMaterialsOrInterviews() {
        String material = upload("a");
        assertThrows(InterviewException.class, () -> materials.get("b", material));
        assertThrows(InterviewException.class, () -> service.start("b", material, "", 5, (s,p)->"问题"));
        var session = service.start("a", material, "", 5, (s,p)->"问题");
        assertThrows(InterviewException.class, () -> service.get("b", session.sessionId()));
        assertThrows(InterviewException.class, () -> service.finish("b", session.sessionId()));
    }
    @Test void validatesModelAndInputAndAllowsEarlyFinishWithoutModel() {
        String material = upload("a");
        assertThrows(InterviewException.class, () -> service.start("a", material, "", 5, null));
        assertThrows(IllegalArgumentException.class, () -> service.start("a", material, "", 11, (s,p)->"问题"));
        var session = service.start("a", material, "", 5, (s,p)->"问题");
        assertThrows(IllegalArgumentException.class, () -> service.answer("a", session.sessionId(), session.currentQuestion().questionId(), " ", (s,p)->"问题"));
        assertEquals("FINISHED", service.finish("a", session.sessionId()).status());
        assertEquals("FINISHED", service.finish("a", session.sessionId()).status());
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), session.currentQuestion().questionId(), "答案", (s,p)->"问题"));
    }
    @Test void rejectsEmptyUnsupportedAndOverlongMaterials() {
        assertThrows(IllegalArgumentException.class, () -> materials.upload("a", new MockMultipartFile("file", "resume.exe", "application/octet-stream", new byte[]{1})));
        assertThrows(IllegalArgumentException.class, () -> materials.upload("a", new MockMultipartFile("file", "resume.txt", "text/plain", "  ".getBytes())));
        assertThrows(IllegalArgumentException.class, () -> materials.upload("a", new MockMultipartFile("file", "resume.txt", "text/plain", "a".repeat(20001).getBytes())));
    }
}
