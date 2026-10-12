package com.shijie.resumehelper.job.match;

import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.domain.UserProfileSnapshot;
import com.shijie.resumehelper.job.util.TextKit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 岗位匹配服务（对应产品方案 5 的 {@code JobMatchService}）。
 *
 * <p>职责：
 * <ol>
 *   <li>用 {@link MatchScorer} 算出可解释的规则分——大模型不可用时岗位页也能正常工作。</li>
 *   <li>再用大模型补充「为什么匹配 / 目前缺少什么」的自然语言解释。</li>
 *   <li>产出技能差距报告。</li>
 * </ol>
 *
 * <p>注意：解释里绝不能出现「录取概率」「一定拿到 offer」这类表述，只描述资料与岗位的贴合程度。
 */
public final class JobMatchService {

    private static final String SYSTEM_PROMPT = """
            你是一名面向应届生的求职顾问，服务于「拾阶求职工作台」的岗位匹配页面。
            你的任务是用中文解释一个岗位和这位求职者的贴合程度，并指出需要补强的地方。

            硬性规则：
            1. 只依据给定的求职者资料和岗位信息，禁止编造求职者没有的经历、证书或数字。
            2. 匹配度表示「资料与岗位的贴合程度」，绝不是录取概率，禁止出现「录取率」「一定能拿到」这类说法。
            3. 输出 2 句话，总共不超过 90 个汉字，不要分点，不要加标题，不要用 markdown。
            4. 第一句说为什么贴合（引用具体的技能或经历），第二句说目前最需要补的一点。
            """;

    /** 技能差距报告，供前端「岗位匹配与差距分析」页面渲染。 */
    public record GapReport(
            String jobId,
            String jobTitle,
            int score,
            List<String> strengths,
            List<String> gaps,
            List<String> suggestions,
            String aiSummary) {
    }

    private final MatchScorer scorer;
    private final AiClient aiClient;
    private final int explainTopN;

    /** 纯规则模式：不需要大模型。 */
    public JobMatchService() {
        this(new MatchScorer(), null, 0);
    }

    public JobMatchService(MatchScorer scorer) {
        this(scorer, null, 0);
    }

    /**
     * @param scorer     规则打分器
     * @param aiClient   大模型客户端，可为 null（表示当前请求没有可用 token）
     * @param explainTopN 只给排名前 N 的岗位调用大模型，控制耗时和 token 消耗
     */
    public JobMatchService(MatchScorer scorer, AiClient aiClient, int explainTopN) {
        this.scorer = scorer == null ? new MatchScorer() : scorer;
        this.aiClient = aiClient;
        this.explainTopN = Math.max(0, explainTopN);
    }

    public MatchScorer scorer() {
        return scorer;
    }

    /** 只做规则排序，不调用大模型。用于搜索列表接口（要快）。 */
    public List<JobMatchResult> rank(Collection<Job> jobs, UserProfileSnapshot profile, int limit) {
        return scorer.rank(jobs, profile, limit);
    }

    /** 单岗位匹配：规则打分 + AI 解释，对应 {@code POST /api/jobs/{id}/match}。 */
    public JobMatchResult match(Job job, UserProfileSnapshot profile) {
        return explain(scorer.score(job, profile), profile);
    }

    /** 给匹配结果补上 AI 解释；AI 不可用时退回规则解释。 */
    public JobMatchResult explain(JobMatchResult result, UserProfileSnapshot profile) {
        String fallback = ruleExplanation(result);
        if (!hasRealAi()) {
            return result.withAiExplanation(fallback);
        }
        try {
            String text = sanitize(aiClient.chat(SYSTEM_PROMPT, userPrompt(result, profile)));
            return result.withAiExplanation(text.isEmpty() ? fallback : text);
        } catch (AiClient.AiException e) {
            // 大模型挂了也要给出解释，岗位页不能因此空白
            return result.withAiExplanation(fallback);
        } catch (RuntimeException e) {
            return result.withAiExplanation(fallback);
        }
    }

    /** 排序 + 只对前 N 个岗位做 AI 解释。 */
    public List<JobMatchResult> rankWithExplanation(Collection<Job> jobs, UserProfileSnapshot profile, int limit) {
        List<JobMatchResult> ranked = rank(jobs, profile, limit);
        if (!hasRealAi() || explainTopN == 0) {
            List<JobMatchResult> plain = new ArrayList<>();
            for (JobMatchResult result : ranked) {
                plain.add(result.withAiExplanation(ruleExplanation(result)));
            }
            return plain;
        }
        List<JobMatchResult> explained = new ArrayList<>(ranked.size());
        for (int i = 0; i < ranked.size(); i++) {
            explained.add(i < explainTopN ? explain(ranked.get(i), profile) : ranked.get(i).withAiExplanation(ruleExplanation(ranked.get(i))));
        }
        return explained;
    }

    /** 生成技能差距报告（对应产品方案 12 提到的「技能差距报告」）。 */
    public GapReport gapReport(Job job, UserProfileSnapshot profile) {
        JobMatchResult result = scorer.score(job, profile);
        List<String> strengths = new ArrayList<>(result.reasons());
        if (!result.matchedSkills().isEmpty()) {
            strengths.add(0, "已具备岗位要求的 " + String.join("、", result.matchedSkills()));
        }

        List<String> gaps = new ArrayList<>(result.gaps());
        List<String> suggestions = new ArrayList<>();

        List<String> missing = result.missingSkills();
        if (!missing.isEmpty()) {
            int n = Math.min(3, missing.size());
            suggestions.add("优先补 " + String.join("、", missing.subList(0, n))
                    + "：做成一个能写进简历的小项目，面试时才有具体内容可讲");
        }
        if (job.minYears() > profile.yearsOfExperience() + 1) {
            suggestions.add("岗位经验要求偏紧，用项目深度和实习经历去弥补，不要虚构年限");
        }
        if (!profile.targetCities().isEmpty() && !job.city().isEmpty() && !job.cityMatches(profile.targetCities())) {
            suggestions.add("该岗位不在你的期望城市，如果愿意接受，投递时可以说明到岗时间");
        }
        if (suggestions.isEmpty()) {
            suggestions.add("技能与条件基本贴合，重点打磨简历里与岗位描述用词一致的表述，准备 2 个可深挖的项目案例");
        }

        return new GapReport(job.id(), job.title(), result.score(), strengths, gaps, suggestions,
                result.withAiExplanation("").primaryReason());
    }

    /** 差距报告的 AI 版本：在规则报告之上补一段总结。 */
    public GapReport gapReportWithAi(Job job, UserProfileSnapshot profile) {
        GapReport base = gapReport(job, profile);
        if (!hasRealAi()) {
            return base;
        }
        try {
            JobMatchResult result = scorer.score(job, profile);
            String summary = sanitize(aiClient.chat(SYSTEM_PROMPT, userPrompt(result, profile)));
            if (summary.isEmpty()) {
                return base;
            }
            return new GapReport(base.jobId(), base.jobTitle(), base.score(), base.strengths(), base.gaps(),
                    base.suggestions(), summary);
        } catch (RuntimeException e) {
            return base;
        }
    }

    /**
     * 是否存在「真实」的大模型客户端。
     *
     * <p>离线替身 {@code FakeAiClient} 只负责确定性地驱动检索循环，
     * 它不会写自然语言解释，所以解释类调用必须退回规则文案，
     * 否则会把「ACTION: finish」这种协议文本当成解释展示给用户。
     */
    private boolean hasRealAi() {
        return aiClient != null && aiClient.available() && !aiClient.isFallback();
    }

    private String ruleExplanation(JobMatchResult result) {
        StringBuilder sb = new StringBuilder();
        if (!result.reasons().isEmpty()) {
            sb.append(result.reasons().get(0));
        }
        if (!result.gaps().isEmpty()) {
            if (sb.length() > 0) {
                sb.append('；');
            }
            sb.append(result.gaps().get(0));
        }
        if (sb.length() == 0) {
            sb.append("根据你的城市、技能和经验条件综合计算出的贴合程度");
        }
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '。') {
            sb.append('。');
        }
        return sb.toString();
    }

    private String userPrompt(JobMatchResult result, UserProfileSnapshot profile) {
        Job job = result.job();
        StringBuilder sb = new StringBuilder();
        sb.append("【求职者资料】\n");
        sb.append("目标职位：").append(orDash(profile.targetTitle())).append('\n');
        sb.append("期望城市：").append(profile.targetCities().isEmpty() ? "未填写" : String.join("、", profile.targetCities())).append('\n');
        sb.append("学历：").append(orDash(profile.education())).append('\n');
        sb.append("相关经验年限：").append(profile.yearsOfExperience()).append(" 年\n");
        sb.append("技能：").append(profile.skills().isEmpty() ? "未填写" : String.join("、", profile.skills())).append('\n');
        sb.append("期望薪资：").append(orDash(profile.expectedSalary())).append('\n');
        if (!profile.summary().isBlank()) {
            sb.append("自我描述：").append(TextKit.truncate(profile.summary(), 300)).append('\n');
        }
        if (!profile.experiences().isEmpty()) {
            sb.append("经历摘要：\n");
            for (String exp : profile.experiences()) {
                sb.append("- ").append(TextKit.truncate(exp, 160)).append('\n');
            }
        }

        sb.append("\n【岗位信息】\n");
        sb.append("职位：").append(job.title()).append('\n');
        sb.append("公司：").append(job.company()).append('\n');
        sb.append("地点：").append(job.place()).append('\n');
        sb.append("薪资：").append(job.salary()).append('\n');
        sb.append("学历要求：").append(job.education()).append('\n');
        sb.append("经验要求：").append(job.experience()).append('\n');
        sb.append("技能要求：").append(job.skills().isEmpty() ? "未标注" : String.join("、", job.skills())).append('\n');
        if (!job.description().isBlank()) {
            sb.append("岗位描述：").append(TextKit.truncate(job.description(), 400)).append('\n');
        }

        sb.append("\n【系统算出的规则结果（供参考，不要直接复述数字）】\n");
        sb.append("贴合度得分：").append(result.score()).append("/100\n");
        sb.append("已命中技能：").append(result.matchedSkills().isEmpty() ? "无" : String.join("、", result.matchedSkills())).append('\n');
        sb.append("缺少技能：").append(result.missingSkills().isEmpty() ? "无" : String.join("、", result.missingSkills())).append('\n');
        return sb.toString();
    }

    /** 清掉模型爱加的前缀、markdown 和换行，保证能直接展示在卡片上。 */
    private static String sanitize(String raw) {
        String text = TextKit.orEmpty(raw)
                .replaceAll("(?s)```.*?```", " ")
                .replaceAll("[#*>`]", "")
                .replaceAll("\\s+", " ")
                .trim();
        // 防止模型把禁用词带出来
        text = text.replace("录取概率", "贴合程度").replace("录取率", "贴合程度");
        return TextKit.truncate(text, 200);
    }

    private static String orDash(String value) {
        return TextKit.isBlank(value) ? "未填写" : value;
    }
}
