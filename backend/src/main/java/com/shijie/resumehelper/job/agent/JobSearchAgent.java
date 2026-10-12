package com.shijie.resumehelper.job.agent;

import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.ai.FakeAiClient;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.domain.UserProfileSnapshot;
import com.shijie.resumehelper.job.match.JobMatchService;
import com.shijie.resumehelper.job.match.MatchScorer;
import com.shijie.resumehelper.job.source.JobDeduplicator;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.util.TextKit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 岗位检索智能体：负责「上网找适合用户的岗位」这件事。
 *
 * <p>工作循环（ReAct）：
 * <ol>
 *   <li>把用户画像 + 硬性条件 + 已有观察交给大模型；</li>
 *   <li>大模型给出下一步动作（换关键词继续检索 / 结束）；</li>
 *   <li>执行 <code>search_jobs</code> 时调用 {@link JobSourceRegistry} 聚合所有岗位来源；</li>
 *   <li>把观察结果拼回提示词，进入下一轮；</li>
 *   <li>结束后用 {@link JobMatchService} 打分、排序、补充「为什么匹配 / 差在哪里」。</li>
 * </ol>
 *
 * <p><b>降级保证</b>（对应产品方案 11 的兜底要求）：
 * <ul>
 *   <li>没有 token 或大模型报错 -> 用 {@link FakeAiClient} 按规则继续跑，流程不中断；</li>
 *   <li>所有来源都失败 -> 演示岗位库仍会返回结果，岗位页不会是空白；</li>
 *   <li>关键词过窄 -> 注册中心放宽为按硬条件返回。</li>
 * </ul>
 *
 * <p>本类不持有 {@link AiClient}：用户自带 token，客户端必须按请求创建。
 */
public final class JobSearchAgent {

    private static final Logger log = LoggerFactory.getLogger(JobSearchAgent.class);

    private final JobSourceRegistry registry;
    private final MatchScorer scorer;
    private final JobDeduplicator deduplicator = new JobDeduplicator();

    public JobSearchAgent(JobSourceRegistry registry) {
        this(registry, new MatchScorer());
    }

    public JobSearchAgent(JobSourceRegistry registry, MatchScorer scorer) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.scorer = scorer == null ? new MatchScorer() : scorer;
    }

    public JobSourceRegistry registry() {
        return registry;
    }

    /**
     * 跑一次完整的智能体检索。
     *
     * @param aiClient 用户自己的大模型客户端；传 null 表示没有可用 token，会使用离线替身
     */
    public AgentRunResult run(UserProfileSnapshot profile, AgentOptions options, AiClient aiClient) {
        AgentOptions opts = options == null ? AgentOptions.defaults() : options;
        AiClient ai = aiClient == null ? new FakeAiClient() : aiClient;

        List<String> candidateKeywords = KeywordPlanner.plan(profile);
        List<Job> pool = new ArrayList<>();
        List<AgentStep> trace = new ArrayList<>();
        List<JobSourceRegistry.SourceReport> reports = List.of();
        Set<String> usedKeywords = new LinkedHashSet<>();
        List<String> notes = new ArrayList<>();
        String aiSummary = "";
        int lastResultCount = -1;

        if (!profile.isUsable()) {
            notes.add("个人资料不完整（缺少目标职位、技能或经历），匹配度仅供参考，建议先完善个人资料");
        }
        if (candidateKeywords.isEmpty()) {
            notes.add("未能从资料中提取到检索关键词，将直接按城市与经验条件返回岗位");
        }

        for (int round = 1; round <= opts.maxRounds(); round++) {
            AgentAction action = nextAction(ai, profile, opts, round, trace, candidateKeywords, usedKeywords,
                    lastResultCount, notes);

            if (action.isFinish()) {
                aiSummary = action.summary();
                trace.add(new AgentStep(round, action.thought(), action.action(), action.args(),
                        "结束检索：" + (aiSummary.isEmpty() ? "候选岗位已足够" : aiSummary), 0, Instant.now()));
                break;
            }
            if (!action.isSearch()) {
                notes.add("第 " + round + " 轮返回了不支持的动作「" + action.action() + "」，已改用关键词检索");
            }

            JobQuery query = buildQuery(profile, opts, action, candidateKeywords, usedKeywords);
            JobSourceRegistry.Result result = registry.search(query);

            pool.addAll(result.jobs());
            reports = result.reports();
            usedKeywords.add(query.keyword());
            lastResultCount = result.jobs().size();
            if (result.relaxed()) {
                notes.add("关键词「" + query.keyword() + "」过窄，第 " + round + " 轮已放宽为按城市/经验条件返回");
            }

            trace.add(new AgentStep(round, action.thought(), AgentAction.SEARCH_JOBS, argView(query),
                    describe(result, query), lastResultCount, Instant.now()));
        }

        List<Job> candidates = deduplicator.dedupe(pool);

        if (candidates.isEmpty()) {
            // 兜底一：完全按硬条件再搜一次，宁可多给也不要返回空页面
            JobQuery fallback = baseQuery(profile, opts).limit(opts.limit());
            JobSourceRegistry.Result relaxed = registry.search(fallback);
            candidates = deduplicator.dedupe(relaxed.jobs());
            if (!candidates.isEmpty()) {
                reports = relaxed.reports();
                notes.add("按关键词没有命中岗位，已放宽条件返回可用结果");
                trace.add(new AgentStep(trace.size() + 1, "关键词没有命中，放宽条件重查", AgentAction.SEARCH_JOBS,
                        argView(fallback), describe(relaxed, fallback), candidates.size(), Instant.now()));
            }
        }
        if (candidates.isEmpty()) {
            notes.add("当前没有可用的岗位来源，请检查网络，或使用「粘贴岗位描述」入口手动导入岗位");
        }

        JobMatchService matchService = new JobMatchService(scorer, ai, opts.explainTopN());
        List<JobMatchResult> matches = matchService.rankWithExplanation(candidates, profile, opts.limit());

        return new AgentRunResult(profile, matches, trace, reports, candidates.size(),
                List.copyOf(usedKeywords), notes, ai.name(), !ai.isFallback(), aiSummary);
    }

    /**
     * 直接按条件检索并匹配，不跑多轮智能体循环。
     * 用于岗位页的搜索/筛选（要快、要确定），对应 {@code GET /api/jobs}。
     */
    public AgentRunResult search(UserProfileSnapshot profile, JobQuery query, AgentOptions options, AiClient aiClient) {
        AgentOptions opts = options == null ? AgentOptions.defaults() : options;
        AiClient ai = aiClient == null ? new FakeAiClient() : aiClient;
        JobQuery effective = query == null ? baseQuery(profile, opts) : query;

        JobSourceRegistry.Result result = registry.search(effective);
        List<Job> candidates = deduplicator.dedupe(result.jobs());
        List<String> notes = new ArrayList<>();
        if (result.relaxed() && !candidates.isEmpty()) {
            notes.add("关键词过窄，已放宽为按城市/经验条件返回");
        }
        if (candidates.isEmpty()) {
            notes.add("没有匹配的岗位，可以试试更短的关键词，或用「粘贴岗位描述」导入目标岗位");
        }

        JobMatchService matchService = new JobMatchService(scorer, ai, opts.explainTopN());
        List<JobMatchResult> matches = matchService.rankWithExplanation(candidates, profile, effective.limit());
        List<AgentStep> trace = List.of(new AgentStep(1, "按筛选条件直接检索", AgentAction.SEARCH_JOBS,
                argView(effective), describe(result, effective), candidates.size(), Instant.now()));

        return new AgentRunResult(profile, matches, trace, result.reports(), candidates.size(),
                effective.allKeywords(), notes, ai.name(), !ai.isFallback(), "");
    }

    /** 问大模型下一步做什么；失败时降级为规则动作。 */
    private AgentAction nextAction(AiClient ai,
                                   UserProfileSnapshot profile,
                                   AgentOptions options,
                                   int round,
                                   List<AgentStep> trace,
                                   List<String> candidateKeywords,
                                   Set<String> usedKeywords,
                                   int lastResultCount,
                                   List<String> notes) {
        String prompt = ReActProtocol.userPrompt(profile, options, round, trace, candidateKeywords,
                List.copyOf(usedKeywords), lastResultCount);
        try {
            String response = ai.chat(ReActProtocol.systemPrompt(), prompt);
            AgentAction action = ReActProtocol.parse(response);
            if (AgentAction.UNKNOWN.equals(action.action())) {
                notes.add("第 " + round + " 轮模型输出无法解析，已改用规则检索");
            }
            return action;
        } catch (AiClient.AiException e) {
            log.warn("大模型调用失败，降级为规则检索：{}", e.getMessage());
            notes.add("大模型调用失败（" + e.getMessage() + "），已降级为规则检索");
            return AgentAction.unknown("");
        } catch (RuntimeException e) {
            log.warn("大模型调用出现未预期异常，降级为规则检索", e);
            notes.add("大模型调用异常，已降级为规则检索");
            return AgentAction.unknown("");
        }
    }

    /** 把模型给的关键词和条件拼成一次真实检索。 */
    private JobQuery buildQuery(UserProfileSnapshot profile,
                                AgentOptions options,
                                AgentAction action,
                                List<String> candidateKeywords,
                                Set<String> usedKeywords) {
        JobQuery query = baseQuery(profile, options);

        String keyword = action.arg("keyword");
        if (TextKit.isBlank(keyword)) {
            keyword = candidateKeywords.stream()
                    .filter(k -> !usedKeywords.contains(k))
                    .findFirst()
                    .orElse(candidateKeywords.isEmpty() ? "" : candidateKeywords.get(0));
        }
        query.keyword(keyword);

        String city = action.arg("city");
        if (TextKit.isBlank(city)) {
            city = options.city();
        }
        if (TextKit.isBlank(city)) {
            city = profile.primaryCity();
        }
        query.city(city);

        // 注意：不要写成 `cond ? action.intArg(..) : options.maxMinYears()`。
        // 三元表达式里 int 和 Integer 会做数值提升，为 null 的 Integer 会被自动拆箱成 NPE。
        Integer maxYears = options.maxMinYears();
        if (action.args().containsKey("maxYears")) {
            maxYears = action.intArg("maxYears", 0);
        }
        query.maxMinYears(maxYears);

        Integer salaryMin = options.salaryMin();
        if (action.args().containsKey("salaryMin")) {
            salaryMin = action.intArg("salaryMin", 0);
        }
        query.salaryMin(salaryMin);

        if (action.args().containsKey("campusOnly")) {
            query.campusOnly(action.booleanArg("campusOnly", options.campusOnly()));
        }
        if (action.args().containsKey("jobType")) {
            query.jobType(action.arg("jobType"));
        }

        return query.limit(options.limit());
    }

    /** 只带用户设定的硬性条件，不带关键词。用于兜底重查。 */
    private JobQuery baseQuery(UserProfileSnapshot profile, AgentOptions options) {
        JobQuery query = new JobQuery();
        query.city(options.city().isEmpty() ? profile.primaryCity() : options.city());
        query.jobType(options.jobType());
        query.maxMinYears(options.maxMinYears());
        query.salaryMin(options.salaryMin());
        query.campusOnly(options.campusOnly());
        query.strictCity(options.strictCity());
        return query.limit(options.limit());
    }

    private Map<String, String> argView(JobQuery query) {
        Map<String, String> view = new LinkedHashMap<>();
        view.put("keyword", query.keyword().isEmpty() ? "不限" : query.keyword());
        view.put("city", query.city().isEmpty() ? "不限" : query.city());
        view.put("limit", String.valueOf(query.limit()));
        return view;
    }

    private String describe(JobSourceRegistry.Result result, JobQuery query) {
        StringBuilder sb = new StringBuilder();
        sb.append("关键词「").append(query.keyword().isEmpty() ? "不限" : query.keyword()).append('」');
        if (!query.city().isEmpty()) {
            sb.append("、城市「").append(query.city()).append('」');
        }
        sb.append(" 命中 ").append(result.jobs().size()).append(" 个岗位");
        if (result.relaxed()) {
            sb.append("（关键词过窄，已放宽条件）");
        }
        List<String> failed = result.reports().stream()
                .filter(report -> !report.ok())
                .map(JobSourceRegistry.SourceReport::name)
                .toList();
        if (!failed.isEmpty()) {
            sb.append("；来源异常：").append(String.join("、", failed));
        }
        List<String> samples = result.jobs().stream()
                .limit(3)
                .map(job -> job.title() + "（" + job.company() + "）")
                .toList();
        if (!samples.isEmpty()) {
            sb.append("，例如 ").append(String.join("、", samples));
        }
        return sb.toString();
    }
}
