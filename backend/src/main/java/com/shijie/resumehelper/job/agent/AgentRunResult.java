package com.shijie.resumehelper.job.agent;

import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.job.domain.UserProfileSnapshot;
import com.shijie.resumehelper.job.source.JobSourceRegistry;

import java.util.List;

/**
 * 智能体一次完整运行的结果。
 *
 * @param profile        本次使用的用户画像
 * @param matches        排序后的岗位匹配结果（已带匹配解释）
 * @param trace          执行轨迹，前端可以展示成「智能体正在做什么」
 * @param sourceReports  各岗位来源的执行情况，用于说明数据可信度和排查问题
 * @param candidateCount 进入排序的候选岗位数
 * @param usedKeywords   实际用过的检索关键词
 * @param notes          降级/兜底提示，例如「大模型不可用，已改用规则检索」
 * @param aiModel        实际使用的模型标识
 * @param aiUsed         本次是否真的用到了大模型
 * @param aiSummary      模型给出的收尾总结
 */
public record AgentRunResult(
        UserProfileSnapshot profile,
        List<JobMatchResult> matches,
        List<AgentStep> trace,
        List<JobSourceRegistry.SourceReport> sourceReports,
        int candidateCount,
        List<String> usedKeywords,
        List<String> notes,
        String aiModel,
        boolean aiUsed,
        String aiSummary) {

    public AgentRunResult {
        matches = matches == null ? List.of() : List.copyOf(matches);
        trace = trace == null ? List.of() : List.copyOf(trace);
        sourceReports = sourceReports == null ? List.of() : List.copyOf(sourceReports);
        usedKeywords = usedKeywords == null ? List.of() : List.copyOf(usedKeywords);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    public boolean isEmpty() {
        return matches.isEmpty();
    }
}
