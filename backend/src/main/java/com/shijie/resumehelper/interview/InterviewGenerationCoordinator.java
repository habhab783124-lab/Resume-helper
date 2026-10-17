package com.shijie.resumehelper.interview;

import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 生成操作的认领与回写协调器（计划 4.1 / 4.2 第 4–7 条 / T05）。
 *
 * <p>在会话短锁内认领操作并冻结校验输入；锁外的模型生成由 T06 的 runner 调用，
 * 结果再经 {@link #commitSuccess} / {@link #commitFailure} 在短锁内有条件提交。
 * 同一会话最多一个活跃操作，迟到结果在 revision 变更后被拒。
 *
 * <p>本类是薄包装：认领/回写的状态迁移与持久化都由 {@link InterviewService} 完成，
 * 避免运行期 {@link InterviewService.State} 泄露给协调器。
 */
@Component
public class InterviewGenerationCoordinator {
    private final InterviewService service;

    public InterviewGenerationCoordinator(InterviewService service) {
        this.service = Objects.requireNonNull(service, "service");
    }

    /**
     * 一次认领的不可变输入，含 4.2 第 6 条冻结字段（会话 ID、generationId、
     * 逻辑 revision、源题等）。回写时仅凭这些字段校验，不重新读取可能已变更的会话。
     */
    public record GenerationClaim(String owner, String sessionId, String generationId,
                                  String requestId, InterviewSnapshot.GenerationKind kind,
                                  long expectedRevision) {
        public GenerationClaim {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(sessionId, "sessionId");
            Objects.requireNonNull(generationId, "generationId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * 认领一次生成操作。同会话已有活跃操作时加入现有操作、返回其 generationId，
     * 不排第二个任务；否则校验 revision 与前置条件后新建 QUEUED 操作。
     */
    public GenerationClaim claim(String owner, String id, String requestId,
                                InterviewSnapshot.GenerationKind kind, long expectedRevision) {
        String generationId = service.claimGeneration(owner, id, requestId, kind, expectedRevision);
        return new GenerationClaim(owner, id, generationId, requestId, kind, expectedRevision);
    }

    /** 成功回写：把 runner 生成的题目写入会话，操作置 SUCCEEDED。两次提交只追加一题。 */
    public InterviewService.Session commitSuccess(GenerationClaim claim, InterviewService.GenerationResult result) {
        Objects.requireNonNull(claim, "claim");
        return service.commitGenerationSuccess(claim.owner(), claim.sessionId(),
                claim.generationId(), claim.expectedRevision(), result);
    }

    /** 失败回写：操作置 FAILED 并记录失败，不修改题目或 revision。 */
    public InterviewService.Session commitFailure(GenerationClaim claim, InterviewFailure failure) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(failure, "failure");
        return service.commitGenerationFailure(claim.owner(), claim.sessionId(),
                claim.generationId(), claim.expectedRevision(), failure);
    }
}
