package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/**
 * 模拟面试接口（功能三）。
 *
 * <h2>和材料模块的分工（统一后）</h2>
 * 这里<b>不再提供</b> {@code POST /api/materials} 和 {@code GET /api/materials/{id}}。
 * 原来这两个接口和材料模块（功能一）的路径、方法、consumes 完全相同，
 * Spring 启动时会直接抛 {@code Ambiguous mapping} —— 整个服务起不来。
 * 这是"两个模块各写了一套材料上传"的必然后果。
 *
 * <p>现在简历上传统一由材料模块负责（多了大模型结构化抽取、扫描件识别、持久化），
 * 面试模块在 {@code POST /api/interviews} 里按 {@code materialId} 去材料模块
 * 取简历原文，再交给 {@link InterviewService}。
 *
 * <p>对前端来说 {@code /api/interviews} 和 {@code /api/ai/check} 的路径没变，
 * 只有上传接口返回的结构换成了材料模块的 {@code {material, extraction}}。
 */
@RestController
@RequestMapping("/api")
public class InterviewController {

    private final InterviewService interviews;
    private final InterviewAgent agent;
    private final AiClientResolver clients;
    /** 材料模块。这里只用来按 id 取简历原文，不碰它的上传逻辑。 */
    private final MaterialService materials;
    private final InterviewGenerationCoordinator generations;
    private final InterviewGenerationRunner runner;
    private final InterviewReportRunner reportRunner;

    public InterviewController(InterviewService interviews,
                               InterviewAgent agent,
                               AiClientResolver clients,
                               MaterialService materials,
                               InterviewGenerationCoordinator generations,
                               InterviewGenerationRunner runner,
                               InterviewReportRunner reportRunner) {
        this.interviews = interviews;
        this.agent = agent;
        this.clients = clients;
        this.materials = materials;
        this.generations = generations;
        this.runner = runner;
        this.reportRunner = reportRunner;
    }

    public record StartRequest(String materialId, String targetRole, Integer questionCount,
                               String requestId, Boolean async) {}

    public record AnswerRequest(String questionId, String answer) {}

    /** T04：仅保存回答的幂等载荷（计划 4.2）。 */
    public record AnswerSubmissionRequest(String questionId, String submissionId, String answer) {}
    public record ReportRetryRequest(Boolean async, String requestId) {}

    public record FinishRequest(String requestId, AnswerSubmissionRequest submission) {}

    public record GenerationRequest(String requestId, InterviewSnapshot.GenerationKind kind,
                                    long expectedRevision) {}

    @PostMapping("/ai/check")
    public ApiResponse<InterviewAgent.AiCheck> checkAi(HttpServletRequest request) {
        return ApiResponse.ok(agent.checkConnection(clients.resolve(request)));
    }

    @PostMapping("/interviews")
    public ApiResponse<InterviewService.Session> start(@RequestBody StartRequest body, HttpServletRequest request) {
        if (body.materialId() == null || body.materialId().isBlank())
            throw new IllegalArgumentException("请先上传简历");

        String owner = UserContext.resolve(request);
        if (materials.find(owner, body.materialId()).isEmpty())
            throw new InterviewException(404, "找不到这份简历，可能已经被删除了，请重新上传");

        String resume = materials.originalText(owner, body.materialId()).orElse("");
        if (resume.isBlank())
            // 扫描件会走这条路：材料确实存下来了，只是没有文字层。
            // 给一句能指导下一步的提示，而不是笼统的「简历不存在」。
            throw new InterviewException(400,
                    "这份简历没有可用的文字内容（可能是扫描件或图片）。"
                            + "请改用「粘贴文本」，或换一份可以直接复制文字的简历。");

        AiClient ai = clients.resolve(request);
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请填写模型 API Key 后开始面试");
        boolean async = Boolean.TRUE.equals(body.async());
        String requestId = body.requestId();
        if (async && (requestId == null || requestId.isBlank()))
            throw new IllegalArgumentException("异步开始面试必须提供 requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();
        InterviewService.Session session = interviews.startAsync(owner, body.materialId(), resume,
                body.targetRole(), requestId);
        startOpeningIfQueued(owner, session, ai);
        if (!async) {
            if (session.generation() != null)
                runner.await(session.generation().generationId(), Duration.ofSeconds(125));
            session = interviews.get(owner, session.sessionId());
            throwIfGenerationFailed(session);
        }
        return ApiResponse.ok(interviews.get(owner, session.sessionId()));
    }

    @GetMapping("/interviews/{id}")
    public ApiResponse<InterviewService.Session> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.get(UserContext.resolve(request), id));
    }

    @PostMapping("/interviews/{id}/turns")
    public ApiResponse<InterviewService.Session> answer(@PathVariable String id,
                                                        @RequestBody AnswerRequest body,
                                                        HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        var before = interviews.get(owner, id);
        var previous = before.turns().stream().filter(t -> t.questionId().equals(body.questionId())).findFirst();
        InterviewService.Session saved;
        if (previous.isPresent() && previous.get().answer() != null) {
            if (!previous.get().answer().equals(body.answer() == null ? "" : body.answer().strip()))
                throw new InterviewException(409, "这道题已经回答，不能重复修改");
            saved = before; // legacy same-answer replay remains idempotent across newer submission IDs
        } else {
            String submissionId = UUID.nameUUIDFromBytes((body.questionId() + "\n" + body.answer())
                    .getBytes(StandardCharsets.UTF_8)).toString();
            saved = interviews.saveAnswer(owner, id,
                    new InterviewService.Submission(body.questionId(), submissionId, body.answer()));
        }
        if (!"ACTIVE".equals(saved.status()) || saved.currentQuestion() != null)
            return ApiResponse.ok(saved);

        String requestId = saved.generation() != null
                && (saved.generation().state() == InterviewSnapshot.GenerationState.QUEUED
                    || saved.generation().state() == InterviewSnapshot.GenerationState.RUNNING
                    || saved.generation().state() == InterviewSnapshot.GenerationState.RETRY_WAIT)
                ? saved.generation().requestId() : UUID.randomUUID().toString();
        var claim = generations.claim(owner, id, requestId, InterviewSnapshot.GenerationKind.NEXT, saved.revision());
        runner.start(claim, clients.resolve(request));
        var currentGeneration = interviews.get(owner, id).generation();
        if (currentGeneration != null) runner.await(currentGeneration.generationId(), Duration.ofSeconds(125));
        return ApiResponse.ok(interviews.get(owner, id));
    }

    /** T04：仅保存回答，不调用模型、不生成下一题（计划 4.2）。 */
    @PostMapping("/interviews/{id}/answers")
    public ApiResponse<InterviewService.Session> saveAnswer(@PathVariable String id,
                                                            @RequestBody AnswerSubmissionRequest body,
                                                            HttpServletRequest request) {
        return ApiResponse.ok(interviews.saveAnswer(UserContext.resolve(request), id,
                new InterviewService.Submission(body.questionId(), body.submissionId(), body.answer())));
    }

    /** T06: accept a generation operation and return immediately for status polling. */
    @PostMapping("/interviews/{id}/generations")
    public ApiResponse<InterviewService.Session> generate(@PathVariable String id,
                                                          @RequestBody GenerationRequest body,
                                                          HttpServletRequest request) {
        AiClient ai = clients.resolve(request);
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请填写模型 API Key 后继续生成题目");
        String owner = UserContext.resolve(request);
        var claim = generations.claim(owner, id, body.requestId(), body.kind(), body.expectedRevision());
        runner.start(claim, ai);
        return ApiResponse.ok(interviews.get(owner, id));
    }

    private void startOpeningIfQueued(String owner, InterviewService.Session session, AiClient ai) {
        var generation = session.generation();
        if (generation == null || generation.state() != InterviewSnapshot.GenerationState.QUEUED) return;
        var claim = generations.claim(owner, session.sessionId(), generation.requestId(),
                InterviewSnapshot.GenerationKind.OPENING, session.revision());
        runner.start(claim, ai);
    }

    private static void throwIfGenerationFailed(InterviewService.Session session) {
        var generation = session.generation();
        if (generation == null || generation.state() != InterviewSnapshot.GenerationState.FAILED) return;
        InterviewFailure failure = generation.failure();
        int status = failure != null && failure.retryable() ? 502 : 400;
        throw new InterviewException(status,
                failure == null ? "面试生成失败，请重试" : failure.message(), failure);
    }

    @PostMapping("/interviews/{id}/finish")
    public ApiResponse<InterviewService.Session> finish(@PathVariable String id,
                                                        @RequestBody(required = false) FinishRequest body,
                                                        HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        var before = interviews.get(owner, id);
        AnswerSubmissionRequest finalAnswer = body == null ? null : body.submission();
        String requestId = body == null || body.requestId() == null || body.requestId().isBlank()
                ? UUID.randomUUID().toString() : body.requestId();
        InterviewService.Submission submission = finalAnswer == null ? null
                : new InterviewService.Submission(finalAnswer.questionId(), finalAnswer.submissionId(), finalAnswer.answer());
        var finished = interviews.finish(owner, id, requestId, submission);
        // Cancel only after the terminal snapshot is durable; late task results are rejected by revision/status.
        if (before.generation() != null) runner.cancel(before.generation().generationId());
        return ApiResponse.ok(finished);
    }

    /**
     * 生成面试报告。
     *
     * <p>身份和别处一样走 {@link UserContext}。这个接口是队友后加的，
     * 合并时它用的是原来的 {@code owner(request)}（HTTP session id）辅助方法——
     * 那个方法在统一身份时被删掉了，这里改成和本类其它接口一致。
     */
    @GetMapping("/interviews/{id}/report")
    public ApiResponse<InterviewAgent.InterviewReport> report(@PathVariable String id,
            @RequestParam(defaultValue = "false") boolean async, HttpServletRequest request) {
        if (async) {
            String owner = UserContext.resolve(request);
            return ApiResponse.ok(reportRunner.enqueue(owner, id, "report-get-" + id,
                    () -> clients.resolve(request), false));
        }
        return ApiResponse.ok(interviews.report(UserContext.resolve(request), id, () -> clients.resolve(request), false));
    }

    @PostMapping("/interviews/{id}/report/retry")
    public ApiResponse<InterviewAgent.InterviewReport> retryReport(@PathVariable String id,
            @RequestBody(required = false) ReportRetryRequest body, HttpServletRequest request) {
        if (body != null && Boolean.TRUE.equals(body.async())) {
            String owner = UserContext.resolve(request);
            String requestId = body.requestId() == null || body.requestId().isBlank()
                    ? UUID.randomUUID().toString() : body.requestId();
            return ApiResponse.ok(reportRunner.enqueue(owner, id, requestId,
                    () -> clients.resolve(request), true));
        }
        return ApiResponse.ok(interviews.report(UserContext.resolve(request), id, () -> clients.resolve(request), true));
    }
}
