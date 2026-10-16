package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.shijie.resumehelper.interview.InterviewTestFixtures.*;

/**
 * 面试流程的行为测试。
 *
 * <h2>统一后改了什么</h2>
 * {@link InterviewService} 不再依赖材料模块，{@code start} 直接接收<b>简历原文</b>。
 * 所以这里不再需要构造 MaterialService，也不再需要 MockMultipartFile。
 *
 * <p>原来关于"材料格式/材料越权"的断言，责任已经移到材料模块：
 * 格式与编码见 {@code TextExtractionTest}，路径越权见 {@code SafePathsTest}。
 * 这个类专注于面试自身的流程与隔离，<b>原有断言一条没删</b>。
 */
class InterviewFlowTest {

    private static final String RESUME = "张同学：Java Spring Boot 校园项目，负责 Redis 缓存优化。";

    private final InterviewService service = new InterviewService(new InterviewAgent());

    /** 材料 id 由材料模块生成，面试模块只当它是一个字符串标识。 */
    private String upload(String owner) {
        return "mat-" + owner;
    }

    @Test void fullFlowIncludesResumeAndHistoryAndStopsAtLimit() {
        var calls = new AtomicInteger();
        AiClient ai = (system, prompt) -> {
            if (prompt.contains("selectedAlgorithm")) return algorithmFromPrompt(prompt);
            assertTrue(prompt.contains("Redis"), "简历原文应当进入提示词");
            if (calls.incrementAndGet() > 1) assertTrue(prompt.contains("缓存命中率"), "历史回答应当进入提示词");
            return question("请说明你如何验证缓存效果？");
        };
        var session = service.start("a", upload("a"), RESUME, "Java 后端", 2, ai);
        assertEquals("ACTIVE", session.status());
        var next = service.answer("a", session.sessionId(), session.currentQuestion().questionId(), "缓存命中率达到90%", ai);
        assertEquals(2, next.turns().size());
        var end = next;
        while (end.currentQuestion() != null)
            end = service.answer("a", end.sessionId(), end.currentQuestion().questionId(), "通过监控验证", ai);
        assertEquals("FINISHED", end.status());
        assertNull(end.currentQuestion());
        assertEquals(13, calls.get());
    }

    @Test void failedGenerationPreservesAnswerAndRetriesWithoutSkipping() {
        var session = service.start("a", upload("a"), RESUME, "", 3, (s,p)->question("介绍你的项目？"));
        String question = session.currentQuestion().questionId();
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "我的回答", (s,p)->{throw new AiClient.AiException("secret upstream error");}));
        var failed = service.get("a", session.sessionId());
        assertEquals("我的回答", failed.pendingAnswer());
        assertEquals(question, failed.currentQuestion().questionId());
        assertEquals(1, failed.turns().size());
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "替换回答", (s,p)->question("不应调用")));
        assertEquals("我的回答", service.get("a", session.sessionId()).pendingAnswer());
        var success = service.answer("a", session.sessionId(), question, "我的回答", (s,p)->question("遇到了什么困难？"));
        assertNull(success.pendingAnswer());
        assertEquals(2, success.turns().size());
        assertEquals(success, service.answer("a", session.sessionId(), question, "我的回答", (s,p)->{fail("duplicate invokes AI");return "";}));
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), question, "不同回答", (s,p)->question("x")));
    }

    @Test void cannotAccessAnotherUsersInterview() {
        var session = service.start("a", upload("a"), RESUME, "", 5, (s,p)->question("问题"));
        assertThrows(InterviewException.class, () -> service.get("b", session.sessionId()));
        assertThrows(InterviewException.class, () -> service.finish("b", session.sessionId()));
        assertThrows(InterviewException.class,
                () -> service.answer("b", session.sessionId(), session.currentQuestion().questionId(), "答案", (s,p)->question("问题")));
    }

    @Test void validatesModelAndInputAndAllowsEarlyFinishWithoutModel() {
        String material = upload("a");
        assertThrows(InterviewException.class, () -> service.start("a", material, RESUME, "", 5, null));
        assertEquals(13, service.start("a", material, RESUME, "", 11, (s,p)->question("问题")).questionCount());
        var session = service.start("a", material, RESUME, "", 5, (s,p)->question("问题"));
        assertThrows(IllegalArgumentException.class, () -> service.answer("a", session.sessionId(), session.currentQuestion().questionId(), " ", (s,p)->question("问题")));
        assertEquals("FINISHED", service.finish("a", session.sessionId()).status());
        assertEquals("FINISHED", service.finish("a", session.sessionId()).status());
        assertThrows(InterviewException.class, () -> service.answer("a", session.sessionId(), session.currentQuestion().questionId(), "答案", (s,p)->question("问题")));
    }

    /**
     * 新增：没有简历正文时直接拒绝。
     *
     * <p>对应「扫描件」这类情况——材料确实上传成功了，但没有文字层。
     * 如果放行，模型会基于空白编出一场面试，用户完全不知道发生了什么。
     */
    @Test void rejectsBlankResumeInsteadOfInterviewingNothing() {
        String material = upload("a");
        assertThrows(InterviewException.class, () -> service.start("a", material, "", "", 5, (s,p)->question("问题")));
        assertThrows(InterviewException.class, () -> service.start("a", material, "   \n  ", "", 5, (s,p)->question("问题")));
        assertThrows(InterviewException.class, () -> service.start("a", material, null, "", 5, (s,p)->question("问题")));
    }

    /** 新增：目标岗位过长要被拒绝（原来是隐式校验，这里补上断言）。 */
    @Test void rejectsOverlongTargetRole() {
        assertThrows(IllegalArgumentException.class,
                () -> service.start("a", upload("a"), RESUME, "岗".repeat(201), 5, (s,p)->question("问题")));
    }
}
