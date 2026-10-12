package com.shijie.resumehelper.job.agent;

import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.TextKit;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 智能体的提示词与输出协议（ReAct 风格：思考 -> 动作 -> 观察 -> 再思考）。
 *
 * <p>为什么用「文本行协议」而不是各家厂商的 function calling：
 * <ul>
 *   <li>DeepSeek / 通义 / 豆包 / OpenAI 兼容端点的工具调用字段互有差异，
 *       用户自带 token 意味着模型不固定，文本协议在任何模型上都能工作；</li>
 *   <li>解析规则简单，出错时能清晰降级到规则检索，不会整条链路失败。</li>
 * </ul>
 *
 * <p>模型输出格式：
 * <pre>
 * THOUGHT: 一句话思考
 * ACTION: search_jobs
 * ARGS:
 * keyword=Java
 * city=上海
 * limit=20
 * </pre>
 *
 * <p>提示词尾部会附带一段 {@code ---AGENT_CONTEXT---} 结构化上下文，
 * 真实模型可以忽略它，离线替身 {@code FakeAiClient} 靠它做确定性规划。
 */
public final class ReActProtocol {

    public static final String CONTEXT_BEGIN = "---AGENT_CONTEXT---";
    public static final String CONTEXT_END = "---END---";

    private static final Pattern JSON_ACTION = Pattern.compile("\"action\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern JSON_PAIR = Pattern.compile("\"(keyword|city|limit|maxYears|campusOnly|salaryMin|jobType|summary)\"\\s*:\\s*\"?([^,\"}\\n]+)\"?");
    private static final Pattern ARG_LINE = Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)\\s*[=:：]\\s*(.+)$");
    private static final Pattern INLINE_ARG = Pattern.compile(
            "(?i)\\b(keyword|city|limit|maxYears|maxMinYears|campusOnly|salaryMin|jobType|summary)\\s*[=:：]\\s*([^\\s,;、]+)");

    private ReActProtocol() {
    }

    /** 允许的 ARG 键的别名归一，避免模型写成 MAX_YEARS / maxyears。 */
    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("keyword", "keyword"),
            Map.entry("keywords", "keyword"),
            Map.entry("city", "city"),
            Map.entry("limit", "limit"),
            Map.entry("maxyears", "maxYears"),
            Map.entry("maxminyears", "maxYears"),
            Map.entry("years", "maxYears"),
            Map.entry("campusonly", "campusOnly"),
            Map.entry("salarymin", "salaryMin"),
            Map.entry("jobtype", "jobType"),
            Map.entry("summary", "summary"),
            Map.entry("url", "url"));

    public static String systemPrompt() {
        return """
                你是「拾阶求职工作台」的岗位检索智能体，服务对象是正在找工作的应届毕业生。

                你的目标是：用尽可能少的轮次，找到与这位求职者最贴合的岗位。

                你可以使用以下工具：
                1. search_jobs —— 按关键词和条件检索岗位。
                   ARGS: keyword（必填，要短，例如「Java」「后端开发」，不要写整句）、
                         city（可选，例如「上海」）、limit（可选，默认 20）、
                         campusOnly（可选，true/false）。
                2. finish —— 认为候选岗位已经足够，结束检索，并给出 summary。

                工作方式：
                - 先根据求职者的目标职位和技能，选一个覆盖面最广的关键词开始检索。
                - 看到「观察」结果后，如果命中的岗位数是 0 或者明显偏少，就换一个更宽的关键词再检索一次。
                - 如果已经有 5 个以上候选岗位，直接 finish，不要为了凑轮次而多搜。
                - 最多只能检索几轮，不要重复使用同一个关键词。
                - 关键词必须来自候选清单，不要自己创造公司名或项目名。

                输出格式必须严格如下，不要输出任何额外说明、不要用 markdown 代码块：

                THOUGHT: <一句话说明你为什么这么做>
                ACTION: <search_jobs 或 finish>
                ARGS:
                keyword=<关键词>
                city=<城市，可省略>
                limit=<条数，可省略>

                结束时的例子：

                THOUGHT: 已经拿到足够候选，进入排序与差距分析。
                ACTION: finish
                ARGS:
                summary=<一句话总结>
                """;
    }

    /** 组装本轮的用户提示词。 */
    public static String userPrompt(UserProfileSnapshot profile,
                                    AgentOptions options,
                                    int round,
                                    List<AgentStep> history,
                                    List<String> candidateKeywords,
                                    List<String> usedKeywords,
                                    int lastResultCount) {
        StringBuilder sb = new StringBuilder();
        sb.append("【第 ").append(round).append(" 轮】\n\n");

        sb.append("【求职者资料】\n");
        sb.append("目标职位：").append(dash(profile.targetTitle())).append('\n');
        sb.append("期望城市：").append(profile.targetCities().isEmpty() ? "未填写" : String.join("、", profile.targetCities())).append('\n');
        sb.append("学历：").append(dash(profile.education())).append('\n');
        sb.append("经验年限：").append(profile.yearsOfExperience()).append(" 年\n");
        sb.append("技能：").append(profile.skills().isEmpty() ? "未填写" : String.join("、", profile.skills())).append('\n');
        if (!profile.summary().isBlank()) {
            sb.append("自我描述：").append(TextKit.truncate(profile.summary(), 200)).append('\n');
        }

        sb.append("\n【本次检索的硬性条件】\n");
        sb.append("城市：").append(options.city().isEmpty() ? "不限（作为匹配维度打分）" : options.city()).append('\n');
        sb.append("工作类型：").append(options.jobType().isEmpty() ? "不限" : options.jobType()).append('\n');
        sb.append("是否只看校招/应届：").append(options.campusOnly() ? "是" : "否").append('\n');

        if (!history.isEmpty()) {
            sb.append("\n【已执行的检索与观察】\n");
            for (AgentStep step : history) {
                sb.append("- 第 ").append(step.round()).append(" 轮 ").append(step.action())
                        .append("：").append(step.observation()).append('\n');
            }
        } else {
            sb.append("\n【已执行的检索与观察】\n- 还没有检索过\n");
        }

        sb.append('\n').append(contextBlock(round, options, candidateKeywords, usedKeywords, lastResultCount));
        return sb.toString();
    }

    /**
     * 结构化上下文。真实模型不需要理解它，离线替身靠它规划。
     */
    public static String contextBlock(int round,
                                      AgentOptions options,
                                      List<String> candidateKeywords,
                                      List<String> usedKeywords,
                                      int lastResultCount) {
        StringBuilder sb = new StringBuilder();
        sb.append(CONTEXT_BEGIN).append('\n');
        sb.append("round=").append(round).append('\n');
        sb.append("maxRounds=").append(options.maxRounds()).append('\n');
        sb.append("candidateKeywords=").append(String.join("|", candidateKeywords)).append('\n');
        sb.append("usedKeywords=").append(String.join("|", usedKeywords)).append('\n');
        sb.append("lastResultCount=").append(lastResultCount).append('\n');
        sb.append(CONTEXT_END).append('\n');
        return sb.toString();
    }

    /** 解析模型输出。解析失败返回 {@link AgentAction#UNKNOWN}，由智能体降级处理。 */
    public static AgentAction parse(String response) {
        String text = TextKit.orEmpty(response).trim();
        if (text.isEmpty()) {
            return AgentAction.unknown("");
        }
        String thought = label(text, "THOUGHT", "思考");
        String action = normalizeAction(label(text, "ACTION", "动作", "行动"));
        Map<String, String> args = extractArgs(text);

        if (action.isEmpty()) {
            Matcher m = JSON_ACTION.matcher(text);
            if (m.find()) {
                action = normalizeAction(m.group(1));
            }
        }
        if (action.isEmpty()) {
            return AgentAction.unknown(text);
        }
        return new AgentAction(thought, action, args, text);
    }

    /** 把模型可能写出的各种动作说法归一到三个标准动作。 */
    static String normalizeAction(String raw) {
        String value = TextKit.orEmpty(raw).toLowerCase(Locale.ROOT).trim();
        if (value.isEmpty()) {
            return "";
        }
        if (value.contains("search") || value.contains("检索") || value.contains("搜索") || value.contains("查询")) {
            return AgentAction.SEARCH_JOBS;
        }
        if (value.contains("import") || value.contains("导入")) {
            return AgentAction.IMPORT_JOB;
        }
        if (value.contains("finish") || value.contains("done") || value.contains("结束")
                || value.contains("完成") || value.contains("停止")) {
            return AgentAction.FINISH;
        }
        return value;
    }

    /** 取出形如 "THOUGHT: xxx" 的一行内容。 */
    static String label(String text, String... names) {
        for (String line : text.split("\\R")) {
            String trimmed = line.trim();
            int colon = indexOfAny(trimmed, ':', '：');
            if (colon < 0) {
                continue;
            }
            String key = trimmed.substring(0, colon).trim().toUpperCase(Locale.ROOT).replace("*", "");
            for (String name : names) {
                if (key.equals(name)) {
                    return trimmed.substring(colon + 1).trim();
                }
            }
        }
        return "";
    }

    /** 解析 ARGS 段，并兼容模型把参数写在正文里的情况。 */
    static Map<String, String> extractArgs(String text) {
        Map<String, String> args = new LinkedHashMap<>();
        String[] lines = text.split("\\R");
        boolean inArgs = false;

        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            int colon = indexOfAny(trimmed, ':', '：');
            String head = colon < 0 ? "" : trimmed.substring(0, colon).trim().toUpperCase(Locale.ROOT).replace("*", "");

            if ("ARGS".equals(head) || "参数".equals(head)) {
                inArgs = true;
                String inline = trimmed.substring(colon + 1).trim();
                if (!inline.isEmpty()) {
                    parseInline(inline, args);
                }
                continue;
            }
            if (inArgs) {
                if (head.equals("THOUGHT") || head.equals("ACTION") || head.equals("思考") || head.equals("动作")) {
                    inArgs = false;
                    continue;
                }
                if (trimmed.startsWith(CONTEXT_BEGIN)) {
                    break;
                }
                Matcher m = ARG_LINE.matcher(trimmed);
                if (m.matches()) {
                    put(args, m.group(1), m.group(2));
                    continue;
                }
                inArgs = false;
            }
        }

        // 兜底：模型没写 ARGS 段时，从正文里扫已知键
        Matcher inline = INLINE_ARG.matcher(text);
        while (inline.find()) {
            put(args, inline.group(1), inline.group(2));
        }
        Matcher json = JSON_PAIR.matcher(text);
        while (json.find()) {
            put(args, json.group(1), json.group(2));
        }
        return args;
    }

    private static void parseInline(String inline, Map<String, String> args) {
        for (String part : inline.split("[,;、]")) {
            int eq = indexOfAny(part, '=', ':', '：');
            if (eq > 0) {
                put(args, part.substring(0, eq), part.substring(eq + 1));
            }
        }
    }

    private static void put(Map<String, String> args, String rawKey, String rawValue) {
        String key = TextKit.orEmpty(rawKey).trim().toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        String canonical = KEY_ALIASES.get(key);
        if (canonical == null) {
            return;
        }
        String value = TextKit.orEmpty(rawValue).trim().replaceAll("^[\"']|[\"']$", "").trim();
        if (!value.isEmpty()) {
            args.putIfAbsent(canonical, value);
        }
    }

    private static int indexOfAny(String text, char... candidates) {
        for (int i = 0; i < text.length(); i++) {
            for (char candidate : candidates) {
                if (text.charAt(i) == candidate) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static String dash(String value) {
        return TextKit.isBlank(value) ? "未填写" : value;
    }
}
