package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * 岗位数据源注册中心：并发度不需要很高，但必须做「隔离 + 兜底」。
 *
 * <p>对应产品方案 11 的风险兜底：「招聘网站需要登录或结构变化」时，单个来源失败不能影响整次检索，
 * 演示岗位库仍然要能返回结果，让岗位匹配功能始终可演示。
 */
public final class JobSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(JobSourceRegistry.class);

    private final List<JobSource> sources;
    private final JobDeduplicator deduplicator = new JobDeduplicator();

    public JobSourceRegistry(Collection<JobSource> sources) {
        List<JobSource> list = new ArrayList<>(sources == null ? List.of() : sources);
        // 演示库排最后，真实来源优先，保证合并时真实来源赢
        list.sort(Comparator.comparingInt(s -> s.kind().equals("mock") ? 1 : 0));
        this.sources = List.copyOf(list);
    }

    public List<JobSource> sources() {
        return sources;
    }

    public List<JobSource> enabledSources() {
        return sources.stream().filter(JobSource::enabled).toList();
    }

    /** 单个数据源本次检索的结果报告，用于前端展示来源可用性和排查问题。 */
    public record SourceReport(String name, String kind, boolean ok, int count, String message) {
    }

    /**
     * 检索结果。
     *
     * @param jobs       去重、筛选后的岗位
     * @param reports    各来源的执行情况
     * @param rawCount   各来源返回的原始条数
     * @param dedupedCount 去重后条数
     * @param relaxed    关键词过严导致没有结果时，是否已放宽为「只按硬条件」返回
     */
    public record Result(List<Job> jobs, List<SourceReport> reports, int rawCount, int dedupedCount, boolean relaxed) {
    }

    /**
     * 聚合检索：调用所有启用的数据源 -> 硬条件筛选 -> 关键词筛选 -> 去重 -> 截断。
     */
    public Result search(JobQuery query) {
        List<Job> collected = new ArrayList<>();
        List<SourceReport> reports = new ArrayList<>();

        for (JobSource source : sources) {
            if (!source.enabled()) {
                reports.add(new SourceReport(source.name(), source.kind(), false, 0, "已通过配置关闭"));
                continue;
            }
            try {
                List<Job> found = source.search(query);
                int count = found == null ? 0 : found.size();
                collected.addAll(count == 0 ? List.of() : found);
                reports.add(new SourceReport(source.name(), source.kind(), true, count, ""));
            } catch (JobSource.JobSourceException e) {
                log.warn("岗位来源 [{}] 检索失败：{}", source.name(), e.getMessage());
                reports.add(new SourceReport(source.name(), source.kind(), false, 0, e.getMessage()));
            } catch (RuntimeException e) {
                // 来源实现的意外异常也要隔离，不能让岗位页整体 500
                log.warn("岗位来源 [{}] 出现未预期异常", source.name(), e);
                reports.add(new SourceReport(source.name(), source.kind(), false, 0, "来源异常：" + e.getClass().getSimpleName()));
            }
        }

        int rawCount = collected.size();
        List<Job> hardFiltered = collected.stream().filter(query::accepts).toList();

        List<String> keywords = query.allKeywords();
        List<Job> keywordFiltered = hardFiltered;
        boolean relaxed = false;
        if (!keywords.isEmpty()) {
            List<Job> matched = hardFiltered.stream()
                    .filter(job -> keywords.stream().anyMatch(kw -> query.matchesKeyword(job, kw)))
                    .toList();
            if (matched.isEmpty() && !hardFiltered.isEmpty()) {
                // 关键词太窄（例如用户写的是整句目标岗位），降级为按硬条件返回，宁可多给也不要空手
                relaxed = true;
                keywordFiltered = hardFiltered;
            } else {
                keywordFiltered = matched;
            }
        }

        List<Job> deduped = deduplicator.dedupe(keywordFiltered);
        int dedupedCount = deduped.size();
        if (deduped.size() > query.limit()) {
            deduped = new ArrayList<>(deduped.subList(0, query.limit()));
        }
        return new Result(deduped, reports, rawCount, dedupedCount, relaxed);
    }
}
