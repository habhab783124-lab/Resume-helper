package com.shijie.resumehelper.interview;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 面试会话的完整可序列化快照（schemaVersion=2，对应计划 4.1/4.4/4.6）。
 *
 * <p>只包含可落盘的数据：简历文本、源材料 ID、岗位、问答、冻结标准、专项计划、
 * 幂等信息、生成状态、成功评分、报告缓存与创建/过期时间。
 * <b>不保存</b>会话锁、报告锁、工作 Future、线程、模型客户端或任何凭据——
 * 凭据只在请求执行期存在，终态即释放，重启后不自动恢复带凭据的调用。
 *
 * <p>阶段 A 预留 {@code plan} / {@code progress} / {@code closedRootIds} 与
 * {@link SnapshotTurn#answerStatus} / {@link SnapshotTurn#submissionId}：
 * 新模式行为由后续任务实现，旧规则下 progress/closedRootIds 以默认空值读取。
 */
public record InterviewSnapshot(
        int schemaVersion,
        String sessionId,
        String owner,
        String materialId,
        String resumeText,
        String targetRole,
        int questionCount,
        String status,
        String finishReason,
        String pendingAnswer,
        long revision,
        long stateVersion,
        List<SnapshotTurn> turns,
        Map<String, InterviewQuestion> questionContracts,
        Map<String, QuestionContext> contexts,
        Hot100Catalog.Problem selectedAlgorithm,
        InterviewSpecialty specialty,
        Map<String, InterviewAssessment> assessments,
        InterviewAgent.InterviewReport cachedReport,
        Generation generation,
        String startRequestId,
        InterviewPlan plan,
        Progress progress,
        Set<String> closedRootIds,
        Instant createdAt,
        Instant expiresAt,
        String scoringState,
        String scoringOperationId,
        String scoringRequestId,
        String scoringError,
        long scoringStateVersion) {

    /** 当前唯一支持的文件结构版本；读取到其他版本必须报受控错误而不是猜测。 */
    public static final int SCHEMA_VERSION = 2;

    public InterviewSnapshot {
        if (schemaVersion != SCHEMA_VERSION)
            throw new IllegalArgumentException("不支持的快照版本：" + schemaVersion);
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(expiresAt, "expiresAt");
        turns = turns == null ? List.of() : List.copyOf(turns);
        // 题目/契约的顺序驱动报告展示，必须用 LinkedHashMap 保留落盘时的文档序
        questionContracts = orderedCopy(questionContracts);
        contexts = orderedCopy(contexts);
        assessments = orderedCopy(assessments);
        closedRootIds = closedRootIds == null ? Set.of() : Set.copyOf(closedRootIds);
        progress = progress == null ? Progress.empty() : progress;
    }

    /** Reads/writes schema-v2 snapshots created before T18 introduced report job state. */
    public InterviewSnapshot(int schemaVersion, String sessionId, String owner, String materialId, String resumeText,
            String targetRole, int questionCount, String status, String finishReason, String pendingAnswer,
            long revision, long stateVersion, List<SnapshotTurn> turns, Map<String, InterviewQuestion> questionContracts,
            Map<String, QuestionContext> contexts, Hot100Catalog.Problem selectedAlgorithm, InterviewSpecialty specialty,
            Map<String, InterviewAssessment> assessments, InterviewAgent.InterviewReport cachedReport, Generation generation,
            String startRequestId, InterviewPlan plan, Progress progress, Set<String> closedRootIds,
            Instant createdAt, Instant expiresAt) {
        this(schemaVersion, sessionId, owner, materialId, resumeText, targetRole, questionCount, status, finishReason,
                pendingAnswer, revision, stateVersion, turns, questionContracts, contexts, selectedAlgorithm, specialty,
                assessments, cachedReport, generation, startRequestId, plan, progress, closedRootIds, createdAt, expiresAt,
                "NOT_STARTED", null, null, null, 0);
    }

    private static <V> Map<String, V> orderedCopy(Map<String, V> source) {
        return source == null
                ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    /**
     * 一条问答记录。{@code answerStatus} 区分未答/已答/跳过/被替换；
     * {@code submissionId} 是回答保存的幂等标识。阶段 A 只产生 UNANSWERED / ANSWERED。
     */
    public record SnapshotTurn(String questionId, String question, String answer,
                               AnswerStatus answerStatus, String submissionId) {
        public SnapshotTurn {
            Objects.requireNonNull(questionId, "questionId");
            answerStatus = answerStatus == null ? AnswerStatus.UNANSWERED : answerStatus;
        }
    }
    public enum AnswerStatus { UNANSWERED, ANSWERED, SKIPPED, WITHDRAWN }

    /** 题目在会话中的结构位置，等价于运行期的 {@link InterviewService.Context}。 */
    public record QuestionContext(InterviewService.Kind kind, String parentQuestionId,
                                  int mainQuestionIndex, Hot100Catalog.Algorithm algorithm) {
        public QuestionContext {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * 一次出题操作（计划 4.1）。{@code attempt} 是本操作累计实际模型请求数（1..6），
     * {@code retryCount} 是实际发生的额外重试数，UI 显示为 n/5。
     */
    public record Generation(String generationId, String requestId,
                             GenerationKind kind, GenerationStage stage, GenerationState state,
                             int attempt, int retryCount, int maxRetries,
                             Instant startedAt, Instant deadlineAt, Instant nextRetryAt,
                             InterviewFailure failure) {
        public Generation {
            Objects.requireNonNull(generationId, "generationId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(stage, "stage");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(startedAt, "startedAt");
            Objects.requireNonNull(deadlineAt, "deadlineAt");
            if (attempt < 0 || retryCount < 0 || maxRetries < 0)
                throw new IllegalArgumentException("生成操作的次数计数无效");
        }
    }
    public enum GenerationKind { OPENING, NEXT }
    public enum GenerationStage { OPENING, NEXT_DECISION, SPECIALTY }
    public enum GenerationState { QUEUED, RUNNING, RETRY_WAIT, SUCCEEDED, FAILED, CANCELLED }

    /** 计划推进进度（计划 4.6）；legacy-v3 会话以 {@link #empty()} 读取，不回填推断值。 */
    public record Progress(int plannedRoots, int resolvedRoots, int answeredRoots,
                           int skippedRoots, int followUpsUsed, Integer followUpBudget) {
        public static Progress empty() {
            return new Progress(0, 0, 0, 0, 0, null);
        }
    }
}
