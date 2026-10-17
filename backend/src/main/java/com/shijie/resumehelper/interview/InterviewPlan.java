package com.shijie.resumehelper.interview;

import java.util.List;
import java.util.Objects;

/**
 * 冻结的面试计划（计划 4.6）。
 *
 * <p>{@code policyVersion} 区分规则世代：{@code legacy-v3} 保留既有 12+1、
 * 单主问题最多两次追问的规则；{@code experience-v1} 引入 QUICK/FULL 与整场追问预算。
 * 它与快照的 {@code schemaVersion}（只描述文件结构）不能混用。
 *
 * <p>legacy-v3 的 {@code level} / {@code careerStage} / {@code followUpBudget} 必须保持
 * {@code null}——用户从未做过这些选择，不能伪造。
 */
public record InterviewPlan(
        String policyVersion,
        Mode mode,
        int mainCount,
        int plannedCount,
        Integer followUpBudget,
        int maxFollowUpsPerMain,
        Level level,
        CareerStage careerStage,
        String targetRole,
        String jobDescription,
        Specialty specialty,
        List<Coverage> coverage,
        ResumeSource source,
        String sourceLabel,
        String sourceFingerprint,
        List<PracticeTarget> practiceTargets,
        String sourceSessionId) {

    public enum Mode { QUICK, FULL }
    public enum Level { FOUNDATION, STANDARD, ADVANCED }
    public enum CareerStage { INTERNSHIP, CAMPUS, EXPERIENCED }

    public InterviewPlan {
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(targetRole, "targetRole");
        Objects.requireNonNull(specialty, "specialty");
        Objects.requireNonNull(source, "source");
        if (mainCount < 1 || plannedCount < mainCount || maxFollowUpsPerMain < 0)
            throw new IllegalArgumentException("计划题数或追问上限无效");
        jobDescription = jobDescription == null ? "" : jobDescription;
        sourceLabel = sourceLabel == null ? "" : sourceLabel;
        sourceFingerprint = sourceFingerprint == null ? "" : sourceFingerprint;
        coverage = coverage == null ? List.of() : List.copyOf(coverage);
        practiceTargets = practiceTargets == null ? List.of() : List.copyOf(practiceTargets);
        sourceSessionId = sourceSessionId == null ? "" : sourceSessionId;
    }

    public InterviewPlan(String policyVersion, Mode mode, int mainCount, int plannedCount, Integer followUpBudget,
            int maxFollowUpsPerMain, Level level, CareerStage careerStage, String targetRole, String jobDescription,
            Specialty specialty, List<Coverage> coverage, ResumeSource source, String sourceLabel,
            String sourceFingerprint, List<PracticeTarget> practiceTargets) {
        this(policyVersion, mode, mainCount, plannedCount, followUpBudget, maxFollowUpsPerMain, level, careerStage,
                targetRole, jobDescription, specialty, coverage, source, sourceLabel, sourceFingerprint,
                practiceTargets, "");
    }

    /** Enforces both the per-root cap and the session-wide allowance; legacy plans have no session cap. */
    public boolean allowsFollowUp(long sessionFollowUps, long rootFollowUps) {
        if (sessionFollowUps < 0 || rootFollowUps < 0)
            throw new IllegalArgumentException("追问计数不能为负数");
        return rootFollowUps < maxFollowUpsPerMain
                && (followUpBudget == null || sessionFollowUps < followUpBudget);
    }

    /**
     * 阶段 A 既有会话的冻结默认计划：12 主问题 + 1 专项、单题最多两次追问、无整场上限。
     * 专项在首题生成成功时才确定，此处以 {@link SpecialtyKind#NONE} 占位。
     */
    public static InterviewPlan legacyV3(ResumeSource source, String targetRole) {
        return new InterviewPlan("legacy-v3", Mode.FULL,
                InterviewService.MAIN_COUNT, InterviewService.QUESTION_COUNT,
                null, 2, null, null, targetRole, "",
                new Specialty(SpecialtyKind.NONE, "", ""), List.of(),
                source, "", "", List.of(), "");
    }

    public record Specialty(SpecialtyKind kind, String focus, String reason) {
        public Specialty {
            Objects.requireNonNull(kind, "kind");
            focus = focus == null ? "" : focus;
            reason = reason == null ? "" : reason;
        }
    }
    public enum SpecialtyKind { NONE, ALGORITHM, CASE_STUDY }

    public record Coverage(String id, String label) {
        public Coverage {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
        }
    }

    public record ResumeSource(SourceKind kind, String id) {
        public ResumeSource {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }
    }
    public enum SourceKind { MATERIAL, RESUME_VERSION }

    public record PracticeTarget(String issueId, String title, String action, List<String> sourceQuestionIds) {
        public PracticeTarget {
            Objects.requireNonNull(issueId, "issueId");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(action, "action");
            sourceQuestionIds = sourceQuestionIds == null ? List.of() : List.copyOf(sourceQuestionIds);
        }
    }
}
