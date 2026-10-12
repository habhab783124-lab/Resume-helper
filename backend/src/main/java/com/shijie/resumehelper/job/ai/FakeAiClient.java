package com.shijie.resumehelper.job.ai;

import com.shijie.resumehelper.job.util.TextKit;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 离线替身大模型：没有配置 token 也能把「智能体检索 -> 匹配 -> 差距分析」整条链路跑通。
 *
 * <p>用途：
 * <ul>
 *   <li>单元测试和本地演示不依赖外网与额度（对应产品方案 11 的兜底要求）。</li>
 *   <li>大模型鉴权失败或超时时，服务层可以切到本实现，保证岗位页不空白。</li>
 * </ul>
 *
 * <p>它<b>不是</b>在假装智能：它只读取提示词尾部的 {@code ---AGENT_CONTEXT---} 结构化上下文，
 * 按确定性规则给出下一步动作，用于驱动真实的智能体循环。真正的规划能力来自大模型实现。
 */
public final class FakeAiClient implements AiClient {

    private static final Pattern CONTEXT = Pattern.compile(
            "---AGENT_CONTEXT---(.*?)---END---", Pattern.DOTALL);
    private static final Pattern KEYWORD_LINE = Pattern.compile("candidateKeywords=(.*)");
    private static final Pattern ROUND_LINE = Pattern.compile("round=(\\d+)");
    private static final Pattern RESULT_LINE = Pattern.compile("lastResultCount=(-?\\d+)");
    private static final Pattern USED_LINE = Pattern.compile("usedKeywords=(.*)");

    private final String modelName;

    public FakeAiClient() {
        this("offline-rule-simulator");
    }

    public FakeAiClient(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public String name() {
        return modelName;
    }

    @Override
    public boolean isFallback() {
        return true;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        Context context = Context.parse(userPrompt);
        if (context.candidateKeywords.isEmpty()) {
            return "THOUGHT: 没有可用关键词，直接结束。\nACTION: finish\nARGS:\nsummary=资料不足，无法生成检索关键词。";
        }

        // 还有没用过的关键词 -> 继续检索；都用过了或者已有结果 -> 收尾
        String next = null;
        for (String keyword : context.candidateKeywords) {
            if (!context.usedKeywords.contains(keyword)) {
                next = keyword;
                break;
            }
        }

        if (next != null && (context.lastResultCount <= 0)) {
            return "THOUGHT: 上一轮命中 " + context.lastResultCount + " 个岗位，换关键词「" + next + "」扩大范围。\n"
                    + "ACTION: search_jobs\n"
                    + "ARGS:\n"
                    + "keyword=" + next + "\n"
                    + "limit=30";
        }
        if (next != null && context.lastResultCount > 0 && context.round <= 2) {
            return "THOUGHT: 已有 " + context.lastResultCount + " 个候选，再补一个关键词提高覆盖度。\n"
                    + "ACTION: search_jobs\n"
                    + "ARGS:\n"
                    + "keyword=" + next + "\n"
                    + "limit=30";
        }
        return "THOUGHT: 候选岗位已足够，进入排序与差距分析。\n"
                + "ACTION: finish\n"
                + "ARGS:\n"
                + "summary=已收集 " + Math.max(context.lastResultCount, 0) + " 个候选岗位，按贴合度排序后返回。";
    }

    /** 提示词尾部的结构化上下文，真实大模型可以忽略它。 */
    private record Context(int round, List<String> candidateKeywords, List<String> usedKeywords, int lastResultCount) {

        static Context parse(String prompt) {
            Matcher block = CONTEXT.matcher(TextKit.orEmpty(prompt));
            if (!block.find()) {
                return new Context(0, List.of(), List.of(), -1);
            }
            String body = block.group(1);
            return new Context(
                    intOf(ROUND_LINE, body, 0),
                    listOf(KEYWORD_LINE, body),
                    listOf(USED_LINE, body),
                    intOf(RESULT_LINE, body, -1));
        }

        private static int intOf(Pattern pattern, String body, int fallback) {
            Matcher m = pattern.matcher(body);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1).trim());
                } catch (NumberFormatException ignored) {
                    return fallback;
                }
            }
            return fallback;
        }

        private static List<String> listOf(Pattern pattern, String body) {
            Matcher m = pattern.matcher(body);
            List<String> values = new ArrayList<>();
            if (m.find() && !m.group(1).isBlank()) {
                for (String part : m.group(1).split("[|、,]")) {
                    String value = part.trim();
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
            }
            return values;
        }
    }
}
