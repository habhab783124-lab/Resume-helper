package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

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

    public InterviewController(InterviewService interviews,
                               InterviewAgent agent,
                               AiClientResolver clients,
                               MaterialService materials) {
        this.interviews = interviews;
        this.agent = agent;
        this.clients = clients;
        this.materials = materials;
    }

    public record StartRequest(String materialId, String targetRole, Integer questionCount) {}

    public record AnswerRequest(String questionId, String answer) {}

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

        return ApiResponse.ok(interviews.start(owner, body.materialId(), resume, body.targetRole(),
                InterviewService.QUESTION_COUNT, clients.resolve(request)));
    }

    @GetMapping("/interviews/{id}")
    public ApiResponse<InterviewService.Session> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.get(UserContext.resolve(request), id));
    }

    @PostMapping("/interviews/{id}/turns")
    public ApiResponse<InterviewService.Session> answer(@PathVariable String id,
                                                        @RequestBody AnswerRequest body,
                                                        HttpServletRequest request) {
        return ApiResponse.ok(interviews.answer(UserContext.resolve(request), id,
                body.questionId(), body.answer(), clients.resolve(request)));
    }

    @PostMapping("/interviews/{id}/finish")
    public ApiResponse<InterviewService.Session> finish(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.finish(UserContext.resolve(request), id));
    }

    /**
     * 生成面试报告。
     *
     * <p>身份和别处一样走 {@link UserContext}。这个接口是队友后加的，
     * 合并时它用的是原来的 {@code owner(request)}（HTTP session id）辅助方法——
     * 那个方法在统一身份时被删掉了，这里改成和本类其它接口一致。
     */
    @GetMapping("/interviews/{id}/report")
    public ApiResponse<InterviewAgent.InterviewReport> report(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.report(UserContext.resolve(request), id, () -> clients.resolve(request), false));
    }

    @PostMapping("/interviews/{id}/report/retry")
    public ApiResponse<InterviewAgent.InterviewReport> retryReport(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.report(UserContext.resolve(request), id, () -> clients.resolve(request), true));
    }
}
