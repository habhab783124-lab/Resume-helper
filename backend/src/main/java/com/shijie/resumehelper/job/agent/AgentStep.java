package com.shijie.resumehelper.job.agent;

import java.time.Instant;
import java.util.Map;

/**
 * 智能体执行轨迹中的一步，用于前端展示「它是怎么找的」和事后排查。
 *
 * @param round       第几轮
 * @param thought     模型思考
 * @param action      动作名
 * @param args        动作参数
 * @param observation 动作执行后的观察结果（人话描述）
 * @param jobCount    这一步新增/命中的岗位数
 * @param at          发生时间
 */
public record AgentStep(
        int round,
        String thought,
        String action,
        Map<String, String> args,
        String observation,
        int jobCount,
        Instant at) {

    public AgentStep {
        args = args == null ? Map.of() : Map.copyOf(args);
        at = at == null ? Instant.now() : at;
    }
}
