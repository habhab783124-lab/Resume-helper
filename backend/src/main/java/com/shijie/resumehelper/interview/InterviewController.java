package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
    private final InterviewQuestionActionService questionActions;
    private final InterviewAgent agent;
    private final AiClientResolver clients;
    private final InterviewSourceResolver sources;
    private final InterviewGenerationCoordinator generations;
    private final InterviewGenerationRunner runner;
    private final InterviewReportRunner reportRunner;
    private final InterviewPlanService plans;
    private final InterviewPracticeService practice;

    public InterviewController(InterviewService interviews,
                               InterviewAgent agent,
                               AiClientResolver clients,
                               InterviewSourceResolver sources,
                               InterviewGenerationCoordinator generations,
                               InterviewGenerationRunner runner,
                               InterviewReportRunner reportRunner,
                               InterviewPlanService plans) {
        this.interviews = interviews;
        this.questionActions = new InterviewQuestionActionService(interviews);
        this.agent = agent;
        this.clients = clients;
        this.sources = sources;
        this.generations = generations;
        this.runner = runner;
        this.reportRunner = reportRunner;
        this.plans = plans;
        this.practice = new InterviewPracticeService(interviews, agent);
    }

    public record StartRequest(String materialId, String targetRole, Integer questionCount,
                               String requestId, Boolean async, InterviewPlan.ResumeSource source,
                               String planId, Integer planVersion, Boolean allowAlgorithmRepeat) {}
    public record SourcePreviewRequest(InterviewPlan.ResumeSource source) {}
    public record FocusedPracticeRequest(List<String> issueIds, String requestId) {}

    public record AnswerRequest(String questionId, String answer) {}

    /** T04：仅保存回答的幂等载荷（计划 4.2）。 */
    public record AnswerSubmissionRequest(String questionId, String submissionId, String answer) {}
    public record SkipRequest(String questionId, String requestId, long expectedRevision) {}
    public record ClarificationRequest(String questionId, String requestId, long expectedRevision) {}
    public record ReplacementRequest(String questionId, String requestId, long expectedRevision, String reason) {}
    public record ReportRetryRequest(Boolean async, String requestId) {}
    public record ReviewRetryRequest(String requestId) {}

    public record FinishRequest(String requestId, AnswerSubmissionRequest submission) {}

    public record GenerationRequest(String requestId, InterviewSnapshot.GenerationKind kind,
                                    long expectedRevision) {}
    public record PracticeFeedbackRequest(String questionId, String requestId, long expectedRevision) {}
    public record AttemptSubmissionRequest(String questionId, String requestId, String answer) {}
    public record AttemptRetryRequest(String requestId) {}

    @PostMapping("/ai/check")
    public ApiResponse<InterviewAgent.AiCheck> checkAi(HttpServletRequest request) {
        return ApiResponse.ok(agent.checkConnection(clients.resolve(request)));
    }

    @DeleteMapping("/interviews/{id}")
    public ApiResponse<Map<String, Boolean>> deleteInterview(@PathVariable String id, HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        InterviewService.Session before = null;
        try { before = interviews.get(owner, id); }
        catch (InterviewException missing) { if (missing.status() != 404) throw missing; }
        interviews.delete(owner, id); // publishes the tombstone before any task is cancelled
        if (before != null && before.generation() != null) runner.cancel(before.generation().generationId());
        reportRunner.cancelSession(id);
        practice.cancelSession(id);
        return ApiResponse.ok(Map.of("deleted", true));
    }

    @GetMapping("/interviews")
    public ApiResponse<InterviewStore.HistoryPage> interviewHistory(
            @RequestParam(defaultValue = "20") int limit,
            @RequestParam(required = false) String cursor, HttpServletRequest request) {
        return ApiResponse.ok(interviews.history(UserContext.resolve(request), limit, cursor));
    }

    @PostMapping("/interviews/source/preview")
    public ApiResponse<InterviewSourceResolver.ResolvedSource> previewSource(
            @RequestBody SourcePreviewRequest body, HttpServletRequest request) {
        if (body == null || body.source() == null) throw new IllegalArgumentException("请选择一份简历");
        return ApiResponse.ok(sources.resolve(UserContext.resolve(request), body.source()));
    }

    @PostMapping("/interviews/plans")
    public ApiResponse<InterviewPlanService.PlanPreview> preparePlan(
            @RequestBody InterviewPlanService.PlanRequest body, HttpServletRequest request) {
        return ApiResponse.ok(plans.prepare(UserContext.resolve(request), body, clients.resolve(request)));
    }

    @PostMapping("/interviews/{id}/practice-plans")
    public ApiResponse<InterviewPlanService.PlanPreview> preparePracticePlan(@PathVariable String id,
            @RequestBody FocusedPracticeRequest body, HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        if (body == null) throw new IllegalArgumentException("请选择报告重点");
        var context = interviews.focusedPracticeContext(owner, id, body.issueIds());
        var source = context.source();
        var planRequest = new InterviewPlanService.PlanRequest(source, context.targetRole(), context.jobDescription(),
                context.careerStage(), context.level(), InterviewPlan.Mode.QUICK, 3,
                InterviewPlanService.SpecialtyPreference.NONE, body.requestId(), id, context.targets());
        return ApiResponse.ok(plans.prepare(owner, planRequest, clients.resolve(request)));
    }

    @GetMapping("/interviews/plans/{planId}")
    public ApiResponse<InterviewPlanService.PlanPreview> getPlan(@PathVariable String planId,
                                                                 HttpServletRequest request) {
        return ApiResponse.ok(plans.getPlan(UserContext.resolve(request), planId));
    }

    @PostMapping("/interviews")
    public ApiResponse<InterviewService.Session> start(@RequestBody StartRequest body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("开始请求不能为空");

        String owner = UserContext.resolve(request);
        AiClient ai = clients.resolve(request);
        boolean async = Boolean.TRUE.equals(body.async());
        String requestId = body.requestId();
        if (async && (requestId == null || requestId.isBlank()))
            throw new IllegalArgumentException("异步开始面试必须提供 requestId");
        if (requestId == null || requestId.isBlank()) requestId = UUID.randomUUID().toString();

        if (body.planId() != null && !body.planId().isBlank()) {
            if (!async) throw new IllegalArgumentException("计划启动必须使用异步模式");
            if (body.source() != null || (body.materialId() != null && !body.materialId().isBlank())
                    || body.targetRole() != null || body.questionCount() != null)
                throw new IllegalArgumentException("计划启动请求不能同时携带直接来源或覆盖计划字段");
            if (body.planVersion() == null) throw new IllegalArgumentException("启动计划必须提供 planVersion");
            if (ai == null || ai.isFallback() || !ai.available())
                throw new InterviewException(503, "请填写模型 API Key 后开始面试");
            InterviewService.Session planned = plans.startPlanned(owner, body.planId(), body.planVersion(), requestId, ai, Boolean.TRUE.equals(body.allowAlgorithmRepeat()));
            return ApiResponse.ok(planned);
        }

        if (body.source() == null && (body.materialId() == null || body.materialId().isBlank()))
            throw new IllegalArgumentException("请先上传简历");
        InterviewPlan.ResumeSource source = body.source() != null ? body.source()
                : new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, body.materialId());
        var resolved = sources.resolve(owner, source);
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请填写模型 API Key 后开始面试");
        InterviewService.Session session = interviews.startAsync(owner, resolved, body.targetRole(), requestId);
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
        if (saved.plan() != null && "experience-v1".equals(saved.plan().policyVersion())
                && saved.plan().mode() == InterviewPlan.Mode.QUICK)
            return ApiResponse.ok(saved); // QUICK waits for the user's next-step choice after saving.

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

    @PostMapping("/interviews/{id}/skip")
    public ApiResponse<InterviewService.Session> skip(@PathVariable String id,
                                                      @RequestBody SkipRequest body,
                                                      HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        var before = interviews.get(owner, id);
        var skipped = questionActions.skip(owner, id, body.questionId(), body.requestId(), body.expectedRevision());
        // Persist closure first; then stop any generation for the now closed question group.
        if (before.generation() != null && switch (before.generation().state()) {
            case QUEUED, RUNNING, RETRY_WAIT -> true;
            default -> false;
        }) runner.cancel(before.generation().generationId());
        return ApiResponse.ok(skipped);
    }

    @PostMapping("/interviews/{id}/feedback")
    public ApiResponse<InterviewService.Session> practiceFeedback(@PathVariable String id,
            @RequestBody PracticeFeedbackRequest body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("反馈请求不能为空");
        return ApiResponse.ok(practice.feedback(UserContext.resolve(request), id, body.questionId(),
                body.requestId(), body.expectedRevision(), clients.resolve(request)));
    }

    @PostMapping("/interviews/{id}/attempts")
    public ApiResponse<InterviewAttempt> submitAttempt(@PathVariable String id,
            @RequestBody AttemptSubmissionRequest body, HttpServletRequest request) {
        if (body == null) throw new IllegalArgumentException("重答内容不能为空");
        return ApiResponse.ok(practice.submitAttempt(UserContext.resolve(request), id, body.questionId(),
                body.requestId(), body.answer(), clients.resolve(request)));
    }

    @GetMapping("/interviews/{id}/attempts")
    public ApiResponse<List<InterviewAttempt>> listAttempts(@PathVariable String id,
            @RequestParam String questionId, HttpServletRequest request) {
        return ApiResponse.ok(practice.listAttempts(UserContext.resolve(request), id, questionId));
    }

    @GetMapping("/interviews/{id}/attempts/{attemptId}")
    public ApiResponse<InterviewAttempt> getAttempt(@PathVariable String id, @PathVariable String attemptId,
            HttpServletRequest request) {
        return ApiResponse.ok(practice.getAttempt(UserContext.resolve(request), id, attemptId));
    }

    @PostMapping("/interviews/{id}/attempts/{attemptId}/retry")
    public ApiResponse<InterviewAttempt> retryAttempt(@PathVariable String id, @PathVariable String attemptId,
            @RequestBody(required = false) AttemptRetryRequest body, HttpServletRequest request) {
        String requestId = body == null || body.requestId() == null || body.requestId().isBlank()
                ? UUID.randomUUID().toString() : body.requestId();
        return ApiResponse.ok(practice.retryAttempt(UserContext.resolve(request), id, attemptId,
                requestId, clients.resolve(request)));
    }

    @PostMapping("/interviews/{id}/clarifications")
    public ApiResponse<InterviewService.Session> clarify(@PathVariable String id,
            @RequestBody ClarificationRequest body, HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        var before = interviews.get(owner, id);
        var metadata = before.questionMetadata().get(body.questionId());
        String rootId = metadata != null && metadata.kind() == InterviewService.Kind.FOLLOW_UP
                ? metadata.parentQuestionId() : body.questionId();
        var previous = before.clarifications().get(rootId);
        if (previous != null && body.requestId().equals(previous.requestId())) return ApiResponse.ok(before);
        AiClient ai = clients.resolve(request);
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请填写模型 API Key 后解释题目");
        var accepted = questionActions.clarify(owner, id, body.questionId(), body.requestId(), body.expectedRevision());
        String question = accepted.turns().stream().filter(turn -> turn.questionId().equals(body.questionId()))
                .map(InterviewService.Turn::question).findFirst().orElseThrow(() -> new InterviewException(404, "题目不存在"));
        CompletableFuture.runAsync(() -> {
            try {
                String explanation = agent.clarifyQuestion(question, ai);
                interviews.completeClarification(owner, id, body.questionId(), body.requestId(), explanation);
            } catch (RuntimeException failure) {
                interviews.failClarification(owner, id, body.questionId(), body.requestId(), "题目解释暂不可用，请重试");
            }
        });
        return ApiResponse.ok(accepted);
    }

    @PostMapping("/interviews/{id}/replacements")
    public ApiResponse<InterviewService.Session> replace(@PathVariable String id,
            @RequestBody ReplacementRequest body, HttpServletRequest request) {
        String owner = UserContext.resolve(request);
        var before = interviews.get(owner, id);
        var previous = before.replacements().get(body.questionId());
        if (previous != null && body.requestId().equals(previous.requestId())) return ApiResponse.ok(before);
        AiClient ai = clients.resolve(request);
        if (ai == null || ai.isFallback() || !ai.available())
            throw new InterviewException(503, "请填写模型 API Key 后重新生成题目");
        var accepted = questionActions.replace(owner, id, body.questionId(), body.requestId(),
                body.expectedRevision(), body.reason());
        var context = interviews.replacementContext(owner, id, body.questionId());
        CompletableFuture.runAsync(() -> {
            try {
                var alternative = agent.prepareReplacement(context.resume(), context.role(), context.question(), body.reason(), ai);
                interviews.completeReplacement(owner, id, body.questionId(), body.requestId(), alternative);
            } catch (RuntimeException failure) {
                interviews.failReplacement(owner, id, body.questionId(), body.requestId(), "替换题暂不可用，请检查模型后重试");
            }
        });
        return ApiResponse.ok(accepted);
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

    @PreDestroy
    public void closePracticeExecutor() { practice.close(); }

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

    @PostMapping("/interviews/{id}/report/review/retry")
    public ApiResponse<InterviewAgent.InterviewReport> retryReview(@PathVariable String id,
            @RequestBody(required = false) ReviewRetryRequest body, HttpServletRequest request) {
        String requestId = body == null || body.requestId() == null || body.requestId().isBlank()
                ? UUID.randomUUID().toString() : body.requestId();
        return ApiResponse.ok(reportRunner.retryReview(UserContext.resolve(request), id, requestId,
                () -> clients.resolve(request)));
    }
}
