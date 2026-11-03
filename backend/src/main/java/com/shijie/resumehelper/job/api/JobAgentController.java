package com.shijie.resumehelper.job.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.job.agent.AgentRunResult;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.job.api.dto.JobRequests;
import com.shijie.resumehelper.job.api.dto.JobViews;
import com.shijie.resumehelper.common.profile.ProfileDto;
import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.common.util.TextKit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.function.Predicate;

/**
 * 岗位检索智能体接口。
 *
 * <p>与 {@link JobController} 的区别：
 * <ul>
 *   <li>{@code GET /api/jobs} 是「按我给的条件查」，一轮出结果，快；</li>
 *   <li>{@code POST /api/jobs/agent/run} 是「让智能体自己决定怎么找」，
 *       它会根据上一轮命中情况换关键词扩检，并把思考过程返回给前端展示。</li>
 * </ul>
 *
 * <p>返回体里的 {@code trace} 就是执行轨迹，前端可以渲染成
 * 「正在检索：后端开发 → 命中 7 个 → 换关键词 Java → 命中 8 个 → 排序完成」。
 *
 * <p>智能体必须使用用户通过请求头提供的密钥；普通岗位搜索不需要密钥。
 */
@RestController
@RequestMapping("/api/jobs/agent")
public class JobAgentController {

    private final JobService jobService;
    private final AiClientResolver aiResolver;

    public JobAgentController(JobService jobService, AiClientResolver aiResolver) {
        this.jobService = jobService;
        this.aiResolver = aiResolver;
    }

    /**
     * 运行岗位检索智能体。
     *
     * <p>请求头（用户自带 token，其中 API Key 必填）：
     * <ul>
     *   <li>{@code X-AI-Api-Key} —— 用户的 API Key</li>
     *   <li>{@code X-AI-Base-Url} —— 自定义 OpenAI 兼容端点</li>
     *   <li>{@code X-AI-Model} —— 模型名</li>
     * </ul>
     */
    @PostMapping("/run")
    public ApiResponse<JobViews.AgentRunView> run(@RequestBody JobRequests.AgentRun request,
                                                  HttpServletRequest http) {
        if (TextKit.isBlank(http.getHeader(AiClientResolver.HEADER_API_KEY))) {
            throw new IllegalArgumentException("请先配置 AI Token，再让智能体寻找岗位；普通搜索和粘贴岗位匹配无需 Token。");
        }
        ProfileDto profile = request == null ? null : request.getProfile();
        String userId = UserContext.resolve(http);
        if (profile != null) profile.setUserId(userId);
        AiClient ai = aiResolver.resolve(http);

        AgentRunResult result = jobService.runAgent(profile,
                request == null ? null : request.getOptions(), ai);

        Predicate<String> savedLookup = jobId -> jobService.isSaved(userId, jobId);
        return ApiResponse.ok(JobViews.AgentRunView.of(result, savedLookup),
                result.aiUsed() ? null : "本次未使用大模型，已用规则策略完成检索");
    }
}
