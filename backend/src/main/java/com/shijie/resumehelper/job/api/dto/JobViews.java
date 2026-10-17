package com.shijie.resumehelper.job.api.dto;

import com.shijie.resumehelper.job.agent.AgentRunResult;
import com.shijie.resumehelper.job.agent.AgentStep;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.match.JobMatchService;
import com.shijie.resumehelper.job.source.JobSource;
import com.shijie.resumehelper.job.source.JobSourceRegistry;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * 岗位相关接口的响应视图。
 *
 * <p>{@link JobCard} 的字段是<b>按前端原型 {@code prototype-static/assets/app.js} 里的
 * 岗位卡片对象逐一对齐的</b>：id / company / mark / style / title / salary / place /
 * experience / tags / score / reason。前端把 mock 数组换成接口返回即可，
 * 不需要改渲染逻辑。
 */
public final class JobViews {

    /** 前端必须原样展示这句话：匹配度不是录取概率。 */
    public static final String SCORE_NOTICE =
            "匹配度表示岗位与你的资料贴合程度，不代表录取概率。";

    private JobViews() {
    }

    /** 岗位卡片。前端岗位页、工作台推荐位共用这一个结构。 */
    public record JobCard(
            String id,
            String title,
            String company,
            /** 公司首字，对应原型里的 company-mark。 */
            String mark,
            /** 公司标记的配色类："" / mark-blue / mark-amber / mark-rose。 */
            String style,
            String salary,
            /** 展示用地点，例如「上海 · 徐汇」。 */
            String place,
            String city,
            String district,
            String experience,
            String education,
            String jobType,
            boolean campus,
            /** 技能标签，对应原型里的 job-tags。 */
            List<String> tags,
            List<String> matchedSkills,
            List<String> missingSkills,
            /** 匹配度。没有用户画像时为 null（前端不展示分数即可）。 */
            Integer score,
            /** 一句话匹配理由，对应原型里的 job-reason。 */
            String reason,
            List<String> gaps,
            Map<String, Integer> dimensions,
            String sourceName,
            String sourceUrl,
            Instant collectedAt,
            String description,
            boolean saved) {

        /** 没有用户画像时的岗位卡片：只展示岗位本身。 */
        public static JobCard of(Job job, boolean saved) {
            return new JobCard(job.id(), job.title(), job.company(), job.companyMark(), styleOf(job.company()),
                    job.salary(), job.place(), job.city(), job.district(), job.experience(), job.education(),
                    job.jobType(), job.campus(), job.skills(), List.of(), List.of(), null, null,
                    List.of(), Map.of(), job.sourceName(), job.sourceUrl(), job.collectedAt(),
                    brief(job.description()), saved);
        }

        /** 岗位详情保留完整 JD，供用户确认后带入面试准备表单。 */
        public static JobCard ofDetailed(Job job, boolean saved) {
            return new JobCard(job.id(), job.title(), job.company(), job.companyMark(), styleOf(job.company()),
                    job.salary(), job.place(), job.city(), job.district(), job.experience(), job.education(),
                    job.jobType(), job.campus(), job.skills(), List.of(), List.of(), null, null,
                    List.of(), Map.of(), job.sourceName(), job.sourceUrl(), job.collectedAt(),
                    job.description() == null ? "" : job.description(), saved);
        }

        public static JobCard of(JobMatchResult match, boolean saved) {
            Job job = match.job();
            return new JobCard(job.id(), job.title(), job.company(), job.companyMark(), styleOf(job.company()),
                    job.salary(), job.place(), job.city(), job.district(), job.experience(), job.education(),
                    job.jobType(), job.campus(), match.displayTags(4), match.matchedSkills(), match.missingSkills(),
                    match.score(), match.primaryReason(), match.gaps(), match.dimensions(),
                    job.sourceName(), job.sourceUrl(), job.collectedAt(), brief(job.description()), saved);
        }

        /** 列表接口不返回整段 JD，避免响应体过大；详情接口返回全文。 */
        private static String brief(String description) {
            if (description == null) {
                return "";
            }
            return description.length() <= 160 ? description : description.substring(0, 160) + "…";
        }

        /** 按公司名稳定地挑一个配色，保证同一家公司在不同页面颜色一致。 */
        private static String styleOf(String company) {
            String[] styles = {"", "mark-blue", "mark-amber", "mark-rose"};
            return styles[Math.abs(company.hashCode()) % styles.length];
        }
    }

    /** 数据源本次执行情况。 */
    public record SourceStatus(String name, String kind, boolean ok, int count, String message) {

        public static SourceStatus of(JobSourceRegistry.SourceReport report) {
            return new SourceStatus(report.name(), report.kind(), report.ok(), report.count(), report.message());
        }
    }

    /** 数据源元信息（{@code GET /api/jobs/sources}）。 */
    public record SourceInfo(String name, String kind, boolean enabled, String description) {

        public static SourceInfo of(JobSource source) {
            return new SourceInfo(source.name(), source.kind(), source.enabled(), source.description());
        }
    }

    /** 智能体执行轨迹的一步。前端可以渲染成时间线，展示「它是怎么找的」。 */
    public record TraceStep(int round, String thought, String action, Map<String, String> args,
                            String observation, int jobCount, Instant at) {

        public static TraceStep of(AgentStep step) {
            return new TraceStep(step.round(), step.thought(), step.action(), step.args(),
                    step.observation(), step.jobCount(), step.at());
        }
    }

    /** 技能差距报告。 */
    public record GapReport(String jobId, String jobTitle, int score, List<String> strengths,
                            List<String> gaps, List<String> suggestions, String aiSummary) {

        public static GapReport of(JobMatchService.GapReport report) {
            return new GapReport(report.jobId(), report.jobTitle(), report.score(), report.strengths(),
                    report.gaps(), report.suggestions(), report.aiSummary());
        }
    }

    /**
     * 岗位列表结果，搜索接口和智能体接口共用。
     *
     * @param notes        降级提示，例如「大模型不可用，已改用规则检索」——前端应原样展示给用户
     * @param aiUsed       本次是否真的用到了大模型
     * @param usedKeywords 实际用过的检索词，方便判断「为什么没搜到」
     */
    public record JobList(
            int total,
            List<JobCard> jobs,
            List<SourceStatus> sources,
            List<String> notes,
            boolean aiUsed,
            String aiModel,
            List<String> usedKeywords,
            String scoreNotice) {

        public static JobList of(AgentRunResult result, Predicate<String> isSaved) {
            List<JobCard> cards = result.matches().stream()
                    .map(match -> JobCard.of(match, isSaved != null && isSaved.test(match.job().id())))
                    .toList();
            return new JobList(cards.size(), cards,
                    result.sourceReports().stream().map(SourceStatus::of).toList(),
                    result.notes(), result.aiUsed(), result.aiModel(), result.usedKeywords(), SCORE_NOTICE);
        }
    }

    /** 单岗位匹配详情。 */
    public record MatchDetail(
            JobCard job,
            Integer score,
            List<String> matchedSkills,
            List<String> missingSkills,
            List<String> reasons,
            List<String> gaps,
            Map<String, Integer> dimensions,
            String aiExplanation,
            GapReport gapReport,
            String scoreNotice) {

        public static MatchDetail of(JobMatchResult match, JobMatchService.GapReport report, boolean saved) {
            return new MatchDetail(JobCard.of(match, saved), match.score(), match.matchedSkills(),
                    match.missingSkills(), match.reasons(), match.gaps(), match.dimensions(),
                    match.aiExplanation(), report == null ? null : GapReport.of(report), SCORE_NOTICE);
        }
    }

    /** 智能体一次运行的完整响应。 */
    public record AgentRunView(
            int total,
            List<JobCard> jobs,
            List<TraceStep> trace,
            List<SourceStatus> sources,
            List<String> notes,
            int candidateCount,
            List<String> usedKeywords,
            String aiModel,
            boolean aiUsed,
            String summary,
            String scoreNotice) {

        public static AgentRunView of(AgentRunResult result, Predicate<String> isSaved) {
            List<JobCard> cards = result.matches().stream()
                    .map(match -> JobCard.of(match, isSaved != null && isSaved.test(match.job().id())))
                    .toList();
            return new AgentRunView(cards.size(), cards,
                    result.trace().stream().map(TraceStep::of).toList(),
                    result.sourceReports().stream().map(SourceStatus::of).toList(),
                    result.notes(), result.candidateCount(), result.usedKeywords(),
                    result.aiModel(), result.aiUsed(), result.aiSummary(), SCORE_NOTICE);
        }
    }
}
