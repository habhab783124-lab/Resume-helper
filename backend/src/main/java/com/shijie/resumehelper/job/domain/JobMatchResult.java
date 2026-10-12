package com.shijie.resumehelper.job.domain;

import com.shijie.resumehelper.common.util.TextKit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 岗位匹配结果。对应产品方案 7 的 JobMatch：用户与岗位的匹配原因及差距。
 * <p>匹配度用于解释岗位与用户资料的贴合情况，<b>不表示录取概率</b>。
 */
public record JobMatchResult(
        Job job,
        int score,
        List<String> matchedSkills,
        List<String> missingSkills,
        List<String> reasons,
        List<String> gaps,
        Map<String, Integer> dimensions,
        String aiExplanation) {

    public JobMatchResult {
        Objects.requireNonNull(job, "job");
        score = Math.max(0, Math.min(100, score));
        matchedSkills = matchedSkills == null ? List.of() : List.copyOf(matchedSkills);
        missingSkills = missingSkills == null ? List.of() : List.copyOf(missingSkills);
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
        gaps = gaps == null ? List.of() : List.copyOf(gaps);
        dimensions = dimensions == null ? Map.of() : Map.copyOf(dimensions);
        aiExplanation = TextKit.orEmpty(aiExplanation);
    }

    /** 附加 AI 解释，保持其余字段不变。 */
    public JobMatchResult withAiExplanation(String explanation) {
        return new JobMatchResult(job, score, matchedSkills, missingSkills, reasons, gaps, dimensions, explanation);
    }

    /**
     * 给前端岗位卡片用的一句话理由：优先 AI 解释，否则用规则理由。
     * 前端原型里每个岗位卡片都有一行「匹配原因」。
     */
    public String primaryReason() {
        if (!aiExplanation.isBlank()) {
            return TextKit.truncate(aiExplanation, 120);
        }
        return reasons.isEmpty() ? "综合你的资料计算出的贴合程度" : TextKit.truncate(reasons.get(0), 120);
    }

    /** 前端卡片上的技能标签：命中的技能优先，不足时补充岗位要求的技能。 */
    public List<String> displayTags(int max) {
        List<String> tags = new ArrayList<>(matchedSkills);
        for (String skill : job.skills()) {
            if (tags.size() >= max) {
                break;
            }
            if (!tags.contains(skill)) {
                tags.add(skill);
            }
        }
        return tags.size() > max ? new ArrayList<>(tags.subList(0, max)) : tags;
    }

    public Map<String, Object> dimensionView() {
        return new LinkedHashMap<>(dimensions);
    }
}
