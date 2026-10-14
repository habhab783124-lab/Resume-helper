package com.shijie.resumehelper.job.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.job.agent.AgentRunResult;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.job.api.dto.JobRequests;
import com.shijie.resumehelper.job.api.dto.JobViews;
import com.shijie.resumehelper.common.profile.ProfileDto;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.common.util.TextKit;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.function.Predicate;

/**
 * 岗位接口（功能二）。
 *
 * <p>路径沿用产品方案 8 的接口草案：{@code GET /api/jobs}、{@code POST /api/jobs/import}、
 * {@code POST /api/jobs/{id}/match}。另外补了几个前端实际需要的：
 * 带画像的检索、收藏、数据源状态。
 *
 * <p>关于 {@code GET /api/jobs} 能带多少画像：GET 只能传扁平的查询参数，
 * 所以这里支持把画像的关键字段（{@code targetTitle} / {@code skills} / {@code targetCities} ...）
 * 直接当查询参数传。字段一多就用 {@code POST /api/jobs/search}，语义等价。
 *
 * <p>所有密钥相关的头（{@code X-AI-Api-Key} 等）都由 {@link AiClientResolver} 解析，
 * 本层只负责传递，不记录。
 */
@RestController
@RequestMapping("/api/jobs")
public class JobController {

    private final JobService jobService;
    private final AiClientResolver aiResolver;

    public JobController(JobService jobService, AiClientResolver aiResolver) {
        this.jobService = jobService;
        this.aiResolver = aiResolver;
    }

    /**
     * 搜索岗位。
     *
     * <p>带画像字段（targetTitle / skills / targetCities ...）时返回带匹配度的排序结果；
     * 不带时返回不带分数的岗位列表。接口契约见 backend/README.md。
     */
    @GetMapping
    public ApiResponse<JobViews.JobList> search(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String city,
            @RequestParam(required = false) String jobType,
            @RequestParam(required = false) Integer maxYears,
            @RequestParam(required = false) Integer salaryMin,
            @RequestParam(required = false) Boolean campusOnly,
            @RequestParam(required = false) Boolean strictCity,
            @RequestParam(required = false) Integer limit,
            @RequestParam(required = false) Integer explainTopN,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String targetTitle,
            @RequestParam(required = false) List<String> skills,
            @RequestParam(required = false) List<String> targetCities,
            @RequestParam(required = false) Integer yearsOfExperience,
            @RequestParam(required = false) String education,
            @RequestParam(required = false) String expectedSalary,
            HttpServletRequest http) {

        JobRequests.Search request = new JobRequests.Search();
        request.setKeyword(keyword);
        request.setCity(city);
        request.setJobType(jobType);
        request.setMaxYears(maxYears);
        request.setSalaryMin(salaryMin);
        request.setCampusOnly(campusOnly);
        request.setStrictCity(strictCity);
        request.setLimit(limit);
        request.setExplainTopN(explainTopN);

        ProfileDto profile = flatProfile(targetTitle, skills, targetCities, yearsOfExperience,
                education, expectedSalary, userId);
        request.setProfile(profile);

        String resolvedUserId = JobService.normalizeUserId(userId);
        if (profile == null) {
            // 没有画像：只列岗位，不算匹配度
            List<Job> jobs = jobService.list(jobService.toQuery(request));
            List<JobViews.JobCard> cards = jobs.stream()
                    .map(job -> JobViews.JobCard.of(job, jobService.isSaved(resolvedUserId, job.id())))
                    .toList();
            return ApiResponse.ok(new JobViews.JobList(cards.size(), cards, List.of(), List.of(),
                    false, "", List.of(), JobViews.SCORE_NOTICE));
        }
        return ApiResponse.ok(runSearch(profile, request, http, resolvedUserId));
    }

    /** 带完整画像的检索（推荐前端用这个：画像字段多，放 body 更干净）。 */
    @PostMapping("/search")
    public ApiResponse<JobViews.JobList> searchWithProfile(@RequestBody JobRequests.Search request,
                                                           HttpServletRequest http) {
        ProfileDto profile = request.getProfile();
        String userId = JobService.normalizeUserId(profile == null ? null : profile.getUserId());
        return ApiResponse.ok(runSearch(profile, request, http, userId));
    }

    /**
     * 粘贴岗位描述导入。
     *
     * <p>产品方案的兜底要求：外部来源不可用时，用户粘贴 JD 也能完成匹配。
     */
    @PostMapping("/import")
    public ApiResponse<JobViews.JobCard> importJob(@RequestBody JobRequests.Import request) {
        Job job = jobService.importJob(request.getText(), request.getSourceUrl());
        return ApiResponse.ok(JobViews.JobCard.of(job, false), "岗位已导入，可以开始匹配");
    }

    /** 数据源清单与可用性说明。 */
    @GetMapping("/sources")
    public ApiResponse<List<JobViews.SourceInfo>> sources() {
        return ApiResponse.ok(jobService.sources().stream().map(JobViews.SourceInfo::of).toList());
    }

    /** 岗位详情（返回完整岗位描述）。 */
    @GetMapping("/{id}")
    public ApiResponse<JobViews.JobCard> detail(@PathVariable String id,
                                                @RequestParam(required = false) String userId) {
        return jobService.find(id)
                .map(job -> ApiResponse.ok(JobViews.JobCard.of(job,
                        jobService.isSaved(JobService.normalizeUserId(userId), job.id()))))
                .orElseGet(() -> ApiResponse.fail("没有找到该岗位，可能已过期，请重新搜索"));
    }

    /**
     * 计算岗位匹配度与技能差距报告。
     *
     * <p>{@code explainAi=false} 时完全不调用大模型，只出规则结果（响应更快）。
     */
    @PostMapping("/{id}/match")
    public ApiResponse<JobViews.MatchDetail> match(@PathVariable String id,
                                                   @RequestBody(required = false) JobRequests.Match request,
                                                   HttpServletRequest http) {
        Job job = jobService.find(id).orElse(null);
        if (job == null) {
            return ApiResponse.fail("没有找到该岗位，可能已过期，请重新搜索");
        }
        ProfileDto profile = request == null ? null : request.getProfile();
        boolean explainAi = request == null || !Boolean.FALSE.equals(request.getExplainAi());
        AiClient ai = explainAi ? aiResolver.resolve(http) : null;
        String userId = JobService.normalizeUserId(profile == null ? null : profile.getUserId());

        JobService.MatchOutcome outcome = jobService.match(job, profile, explainAi, ai);
        return ApiResponse.ok(JobViews.MatchDetail.of(outcome.match(), outcome.report(),
                jobService.isSaved(userId, job.id())));
    }

    // ------------------------------------------------------------ 收藏

    @PostMapping("/{id}/favorite")
    public ApiResponse<Boolean> favorite(@PathVariable String id,
                                         @RequestParam(required = false) String userId) {
        return ApiResponse.ok(jobService.save(JobService.normalizeUserId(userId), id), "已收藏");
    }

    @DeleteMapping("/{id}/favorite")
    public ApiResponse<Boolean> unfavorite(@PathVariable String id,
                                           @RequestParam(required = false) String userId) {
        return ApiResponse.ok(jobService.unsave(JobService.normalizeUserId(userId), id), "已取消收藏");
    }

    @GetMapping("/favorites")
    public ApiResponse<JobViews.JobList> favorites(@RequestParam(required = false) String userId) {
        String resolved = JobService.normalizeUserId(userId);
        List<JobViews.JobCard> cards = jobService.favorites(resolved).stream()
                .map(job -> JobViews.JobCard.of(job, true))
                .toList();
        return ApiResponse.ok(new JobViews.JobList(cards.size(), cards, List.of(), List.of(),
                false, "", List.of(), JobViews.SCORE_NOTICE));
    }

    // ------------------------------------------------------------ 内部

    private JobViews.JobList runSearch(ProfileDto profile, JobRequests.Search request,
                                       HttpServletRequest http, String userId) {
        AiClient ai = aiResolver.resolve(http);
        AgentRunResult result = jobService.search(profile, request, ai);
        Predicate<String> savedLookup = jobId -> jobService.isSaved(userId, jobId);
        return JobViews.JobList.of(result, savedLookup);
    }

    /**
     * 把 GET 的扁平参数拼成画像。
     *
     * @return 关键字段都没传时返回 {@code null}，表示「这次检索不需要匹配度」
     */
    private static ProfileDto flatProfile(String targetTitle, List<String> skills, List<String> targetCities,
                                          Integer yearsOfExperience, String education, String expectedSalary,
                                          String userId) {
        boolean hasSkills = skills != null && !skills.isEmpty();
        boolean hasCities = targetCities != null && !targetCities.isEmpty();
        if (TextKit.isBlank(targetTitle) && !hasSkills && !hasCities
                && yearsOfExperience == null && TextKit.isBlank(education) && TextKit.isBlank(expectedSalary)) {
            return null;
        }
        ProfileDto profile = new ProfileDto();
        profile.setUserId(userId);
        profile.setTargetTitle(targetTitle);
        profile.setSkills(skills);
        profile.setTargetCities(targetCities);
        profile.setYearsOfExperience(yearsOfExperience);
        profile.setEducation(education);
        profile.setExpectedSalary(expectedSalary);
        return profile;
    }
}
