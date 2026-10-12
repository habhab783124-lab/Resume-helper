package com.shijie.resumehelper.job.agent;

import com.shijie.resumehelper.job.util.TextKit;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 智能体本轮决定执行的动作。由大模型输出经 {@link ReActProtocol} 解析得到。
 *
 * @param thought 模型的一句话思考，会展示在前端的「智能体过程」区域
 * @param action  动作名：search_jobs / import_job / finish
 * @param args    动作参数
 * @param raw     模型原始输出，便于排查提示词问题
 */
public record AgentAction(String thought, String action, Map<String, String> args, String raw) {

    public static final String SEARCH_JOBS = "search_jobs";
    public static final String IMPORT_JOB = "import_job";
    public static final String FINISH = "finish";
    /** 模型输出无法解析时的动作，智能体会退回规则检索。 */
    public static final String UNKNOWN = "unknown";

    public AgentAction {
        thought = TextKit.orEmpty(thought);
        action = TextKit.isBlank(action) ? UNKNOWN : action.trim().toLowerCase();
        args = args == null ? Map.of() : Map.copyOf(args);
        raw = TextKit.orEmpty(raw);
    }

    public static AgentAction finish(String thought, String summary) {
        Map<String, String> args = new LinkedHashMap<>();
        args.put("summary", TextKit.orEmpty(summary));
        return new AgentAction(thought, FINISH, args, "");
    }

    public static AgentAction unknown(String raw) {
        return new AgentAction("模型输出无法解析，改用规则检索", UNKNOWN, Map.of(), raw);
    }

    public String arg(String key) {
        String value = args.get(key);
        return value == null ? "" : value.trim();
    }

    public int intArg(String key, int fallback) {
        String value = arg(key);
        if (value.isEmpty()) {
            return fallback;
        }
        try {
            return (int) Double.parseDouble(value.replaceAll("[^0-9.\\-]", ""));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public boolean booleanArg(String key, boolean fallback) {
        String value = arg(key).toLowerCase();
        if (value.isEmpty()) {
            return fallback;
        }
        return switch (value) {
            case "true", "1", "yes", "y", "是" -> true;
            case "false", "0", "no", "n", "否" -> false;
            default -> fallback;
        };
    }

    public boolean isFinish() {
        return FINISH.equals(action);
    }

    public boolean isSearch() {
        return SEARCH_JOBS.equals(action);
    }

    public String summary() {
        return arg("summary");
    }
}
