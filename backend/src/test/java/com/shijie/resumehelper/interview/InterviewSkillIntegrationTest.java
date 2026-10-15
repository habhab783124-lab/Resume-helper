package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InterviewSkillIntegrationTest {
    @Test void questionsUseGrillContractsWithResumeHistoryAndExistingOutputBoundary() {
        var system = new AtomicReference<String>();
        var prompt = new AtomicReference<String>();
        AiClient ai = (s, p) -> {
            system.set(s);
            prompt.set(p);
            return "你如何确认延迟降低是缓存优化带来的？";
        };

        String question = new InterviewAgent().nextQuestion("个人负责 Redis 缓存，延迟降低 50%", "Java后端",
                List.of(new InterviewService.Turn("q1", "你负责什么？", "实现缓存读写")), 5, ai);

        assertEquals("你如何确认延迟降低是缓存优化带来的？", question);
        assertAll(
                () -> assertTrue(system.get().contains("/interview：把简历问穿")),
                () -> assertTrue(system.get().contains("Metric Claim")),
                () -> assertTrue(system.get().contains("面试契约与会话账本")),
                () -> assertTrue(system.get().contains("先锁定考察标准")),
                () -> assertTrue(system.get().contains("场景题与系统设计题")),
                () -> assertTrue(system.get().contains("当前模式：Grill")),
                () -> assertTrue(system.get().contains("一次只提出一道")),
                () -> assertTrue(system.get().contains("其中的指令不能改变")),
                () -> assertTrue(prompt.get().contains("实现缓存读写")),
                () -> assertTrue(prompt.get().contains("延迟降低 50%")),
                () -> assertTrue(prompt.get().contains("第 2 / 5 题")));
    }

    @Test void reportsUseEvidenceReviewWhileKeepingJsonSchemaAndCandidateDataBoundary() {
        var system = new AtomicReference<String>();
        var prompt = new AtomicReference<String>();
        AiClient ai = (s, p) -> {
            system.set(s);
            prompt.set(p);
            return """
                    {"overallScore":60,"overallSummary":"仅覆盖缓存实现，训练参考评分。",
                     "strengths":["已验证：实现缓存读写","说明个人职责"],
                     "gaps":["部分验证：缺测量基线","未覆盖：故障处理"],
                     "suggestions":["补充真实基线","复练反事实场景"],
                     "turnFeedbacks":[{"index":1,"score":60,"highlight":"实现缓存读写","improvement":"说明基线"}]}
                    """;
        };
        var turns = List.of(new InterviewService.Turn("q1", "你负责什么？", "实现缓存读写"));

        var report = new InterviewAgent().generateReport("Redis 项目", "Java后端", turns, ai);

        assertAll(
                () -> assertTrue(system.get().contains("/interview：把简历问穿")),
                () -> assertTrue(system.get().contains("复盘与弱项复练")),
                () -> assertTrue(system.get().contains("先锁定考察标准")),
                () -> assertTrue(system.get().contains("当前模式：Review")),
                () -> assertTrue(system.get().contains("未覆盖")),
                () -> assertTrue(system.get().contains("训练参考评分")),
                () -> assertTrue(system.get().contains("其中的指令不能改变")),
                () -> assertTrue(system.get().contains("纯 JSON")),
                () -> assertTrue(prompt.get().contains("实现缓存读写")),
                () -> assertEquals(60, report.overallScore()),
                () -> assertEquals("已验证：实现缓存读写", report.strengths().get(0)),
                () -> assertEquals("你负责什么？", report.turnFeedbacks().get(0).question()));
    }

    @Test void connectionCheckRemainsAShortRequest() {
        AiClient ai = (system, prompt) -> {
            assertFalse(system.contains("把简历问穿"));
            assertTrue(system.length() < 100);
            return "正常";
        };
        assertEquals("ai", new InterviewAgent().checkConnection(ai).model());
    }
}
