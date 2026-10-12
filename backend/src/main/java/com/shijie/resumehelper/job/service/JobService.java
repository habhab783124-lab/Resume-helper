package com.shijie.resumehelper.job.service;

import com.shijie.resumehelper.job.agent.AgentOptions;
import com.shijie.resumehelper.job.agent.AgentRunResult;
import com.shijie.resumehelper.job.agent.JobSearchAgent;
import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.api.dto.AgentOptionsDto;
import com.shijie.resumehelper.job.api.dto.JobRequests;
import com.shijie.resumehelper.job.api.dto.ProfileDto;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.domain.UserProfileSnapshot;
import com.shijie.resumehelper.job.match.JobMatchService;
import com.shijie.resumehelper.job.match.MatchScorer;
import com.shijie.resumehelper.job.source.JobImporter;
import com.shijie.resumehelper.job.source.JobSource;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.store.JobStore;
import com.shijie.resumehelper.job.util.TextKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 岗位模块对外的服务入口。
 *
 * <p>控制器只做参数绑定和视图转换，业务编排都在这里。
 * 队友的模块（例如面试模块要「按岗位开始面试」）直接注入本类即可，
 * 不需要经 HTTP 绕一圈。
 *
 * <p>{@link AiClient} 由调用方传入而不是从字段取：用户自带 token，
 * 每个请求的客户端都不同，服务本身必须是无状态单例。
 */
@Service
public class JobService {

    private static final Logger log = LoggerFactory.getLogger(JobService.class);

    /** 单岗位匹配的结果：规则/模型解释 + 技能差距报告。 */
    public record MatchOutcome(JobMatchResult match, JobMatchService.GapReport report) {
    }

    private final JobSourceRegistry registry;
    private final JobSearchAgent agent;
    private final JobStore store;
    private final JobImporter importer;
    private final MatchScorer scorer;
    private final JobModuleProperties properties;

    public JobService(JobSourceRegistry registry,
                      JobSearchAgent agent,
                      JobStore store,
                      JobImporter importer,
                      MatchScorer scorer,
                      JobModuleProperties properties) {
        this.registry = registry;
        this.agent = agent;
        this.store = store;
        this.importer = importer;
        this.scorer = scorer;
        this.properties = properties;
    }

    // ------------------------------------------------------------ 检索

    /**
     * 不带画像的岗位列表：只按关键词和筛选条件返回，不算匹配度。
     *
     * <p>对应 {@code GET /api/jobs}。用户还没填个人资料时走这条路径，
     * 前端不展示匹配分即可。
     */
    public List<Job> list(JobQuery query) {
        JobSourceRegistry.Result result = registry.search(query);
        store.putAll(result.jobs());
        return result.jobs();
    }

    /**
     * 带画像的岗位检索：按匹配度排序并给出解释。
     *
     * <p>对应 {@code POST /api/jobs/search}。之所以不用 GET：
     * 用户画像是一整个结构化对象，塞进 query string 既难看又容易超长。
     */
    public AgentRunResult search(ProfileDto profile, JobRequests.Search request, AiClient ai) {
        JobQuery query = toQuery(request);
        AgentOptions options = toOptions(request);
        AgentRunResult result = agent.search(snapshotOf(profile), query, options, ai);
        cacheJobs(result);
        return result;
    }

    /**
     * 让智能体自己决定检索策略并上网找岗位。
     *
     * <p>对应 {@code POST /api/jobs/agent/run}。
     * 这是功能二的核心接口：会返回执行轨迹，前端可以展示「智能体在做什么」。
     */
    public AgentRunResult runAgent(ProfileDto profile, AgentOptionsDto optionsDto, AiClient ai) {
        UserProfileSnapshot snapshot = snapshotOf(profile);
        AgentOptions options = optionsOf(optionsDto);
        AgentRunResult result = agent.run(snapshot, options, ai);
        cacheJobs(result);
        log.info("岗位智能体运行完成：模型={}，真实大模型={}，候选={}，返回={}",
                result.aiModel(), result.aiUsed(), result.candidateCount(), result.matches().size());
        return result;
    }

    // ------------------------------------------------------------ 单岗位

    /** 按 id 取岗位。store 里没有时回源站扫一遍（例如服务刚重启）。 */
    public Optional<Job> find(String id) {
        Optional<Job> cached = store.find(id);
        if (cached.isPresent()) {
            return cached;
        }
        JobQuery wide = new JobQuery().limit(200);
        List<Job> all = registry.search(wide).jobs();
        store.putAll(all);
        return store.find(id);
    }

    /** 计算单个岗位的匹配度与技能差距报告。对应 {@code POST /api/jobs/{id}/match}。 */
    public MatchOutcome match(Job job, ProfileDto profile, boolean explainAi, AiClient ai) {
        UserProfileSnapshot snapshot = snapshotOf(profile);
        JobMatchService service = new JobMatchService(scorer, explainAi ? ai : null,
                explainAi ? 1 : 0);
        JobMatchResult match = service.explain(scorer.score(job, snapshot), snapshot);
        JobMatchService.GapReport report = explainAi
                ? service.gapReportWithAi(job, snapshot)
                : service.gapReport(job, snapshot);
        return new MatchOutcome(match, report);
    }

    /** 导入用户粘贴的岗位描述。对应 {@code POST /api/jobs/import}。 */
    public Job importJob(String text, String sourceUrl) {
        Job job = importer.parse(text, sourceUrl);
        store.put(job);
        return job;
    }

    /** 可用数据源。对应 {@code GET /api/jobs/sources}。 */
    public List<JobSource> sources() {
        return registry.sources();
    }

    // ------------------------------------------------------------ 收藏

    public boolean save(String userId, String jobId) {
        return store.save(userId, jobId);
    }

    public boolean unsave(String userId, String jobId) {
        return store.unsave(userId, jobId);
    }

    public boolean isSaved(String userId, String jobId) {
        return store.isSaved(userId, jobId);
    }

    public List<Job> favorites(String userId) {
        return store.saved(userId);
    }

    // ------------------------------------------------------------ 内部

    private void cacheJobs(AgentRunResult result) {
        store.putAll(result.matches().stream().map(JobMatchResult::job).toList());
    }

    public JobQuery toQuery(JobRequests.Search request) {
        JobQuery query = new JobQuery();
        if (request != null) {
            query.keyword(request.getKeyword())
                    .city(request.getCity())
                    .jobType(request.getJobType())
                    .maxMinYears(request.getMaxYears())
                    .salaryMin(request.getSalaryMin())
                    .campusOnly(Boolean.TRUE.equals(request.getCampusOnly()))
                    .strictCity(Boolean.TRUE.equals(request.getStrictCity()))
                    .limit(limitOf(request.getLimit()));
        } else {
            query.limit(properties.getDefaultLimit());
        }
        return query;
    }

    public AgentOptions toOptions(JobRequests.Search request) {
        AgentOptionsDto dto = new AgentOptionsDto();
        if (request != null) {
            dto.setLimit(request.getLimit());
            dto.setCity(request.getCity());
            dto.setJobType(request.getJobType());
            dto.setMaxYears(request.getMaxYears());
            dto.setSalaryMin(request.getSalaryMin());
            dto.setCampusOnly(request.getCampusOnly());
            dto.setStrictCity(request.getStrictCity());
            // 搜索接口默认不调用大模型：列表要快，而且用户可能一次筛好几次。
            // 需要 AI 解释时显式传 explainTopN，或者改调 POST /api/jobs/agent/run。
            dto.setExplainTopN(request.getExplainTopN() == null ? 0 : request.getExplainTopN());
        }
        return optionsOf(dto);
    }

    /**
     * 把请求里的参数转成 core 层参数。
     *
     * <p>用户没显式传期望薪资时，用画像里的期望薪资下限做筛选参考——
     * 但仍走 {@code salaryMin} 的语义（低于该值直接剔除），保持一致。
     */
    public AgentOptions optionsOf(AgentOptionsDto dto) {
        AgentOptionsDto source = dto == null ? new AgentOptionsDto() : dto;
        return source.toOptions(properties.getMaxRounds(), properties.getDefaultLimit(),
                properties.getExplainTopN(), properties.getMaxLimit());
    }

    public UserProfileSnapshot snapshotOf(ProfileDto profile) {
        return profile == null ? UserProfileSnapshot.builder().build() : profile.toSnapshot();
    }

    private int limitOf(Integer limit) {
        int value = limit == null ? properties.getDefaultLimit() : limit;
        return Math.min(Math.max(1, value), properties.getMaxLimit());
    }

    /** 收藏用的用户标识：没有登录体系时前端传固定值即可。 */
    public static String normalizeUserId(String userId) {
        return TextKit.isBlank(userId) ? "demo-user" : userId.trim();
    }
}
