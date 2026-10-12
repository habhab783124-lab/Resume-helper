package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跨数据源去重与合并。
 *
 * <p>同一个岗位可能同时出现在演示岗位库和真实来源里。去重按「公司 + 岗位名 + 城市」指纹分组，
 * 组内保留信息最完整的一条，并把技能标签取并集，保证前端不会看到重复卡片。
 */
public final class JobDeduplicator {

    /** 演示数据源的名字里带「演示」，用于在合并时优先保留真实来源。 */
    private static final String DEMO_MARKER = "演示";

    public List<Job> dedupe(Collection<Job> jobs) {
        if (jobs == null || jobs.isEmpty()) {
            return List.of();
        }
        Map<String, Job> byFingerprint = new LinkedHashMap<>();
        for (Job job : jobs) {
            if (job == null) {
                continue;
            }
            byFingerprint.merge(job.fingerprint(), job, JobDeduplicator::merge);
        }
        return new ArrayList<>(byFingerprint.values());
    }

    /** 合并两个重复岗位：保留更完整的一条，技能取并集，描述取更长的一条。 */
    static Job merge(Job a, Job b) {
        Job base = richness(a) >= richness(b) ? a : b;
        Job other = base == a ? b : a;

        Set<String> skills = new LinkedHashSet<>(base.skills());
        skills.addAll(other.skills());

        String description = base.description().length() >= other.description().length()
                ? base.description()
                : other.description();
        String sourceUrl = !base.sourceUrl().isEmpty() ? base.sourceUrl() : other.sourceUrl();
        String salary = base.salary().equals("面议") ? other.salary() : base.salary();
        Instant collectedAt = base.collectedAt().isBefore(other.collectedAt())
                ? base.collectedAt()
                : other.collectedAt();

        return base.toBuilder()
                .skills(new ArrayList<>(skills))
                .description(description)
                .salary(salary)
                .source(base.sourceName(), sourceUrl)
                .collectedAt(collectedAt)
                .build();
    }

    /** 信息完整度打分，用于决定合并时保留哪一条。 */
    private static int richness(Job job) {
        int score = 0;
        if (!job.sourceName().contains(DEMO_MARKER)) {
            score += 100;
        }
        if (!job.sourceUrl().isEmpty()) {
            score += 10;
        }
        score += Math.min(50, job.description().length() / 20);
        score += job.skills().size() * 3;
        return score;
    }
}
