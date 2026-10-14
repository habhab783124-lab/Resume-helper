package com.shijie.resumehelper.job.agent;

import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.job.match.SkillLexicon;
import com.shijie.resumehelper.common.util.TextKit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 检索关键词规划：从用户画像推出「应该拿什么词去搜岗位」。
 *
 * <p>这一步是规则兜底，作用是：
 * <ul>
 *   <li>大模型不可用时智能体仍然有词可搜；</li>
 *   <li>给大模型一份候选清单，避免它凭空造词（例如把「应届」当成技能搜索）。</li>
 * </ul>
 *
 * <p>关键词刻意选得短，因为国内岗位标题多为「Java 后端开发工程师」这类长串，
 * 直接拿整句去匹配命中率很低。
 */
public final class KeywordPlanner {

    /** 岗位标题里常见的级别/修饰词，规划时剔除，避免关键词过长。 */
    private static final List<String> NOISE_WORDS = List.of(
            "初级", "中级", "高级", "资深", "专家", "实习", "实习生", "校招", "应届", "兼职", "全职",
            "岗位", "职位", "方向", "（", "）", "(", ")", "—", "-", "/");

    private static final int MAX_KEYWORDS = 6;

    private KeywordPlanner() {
    }

    public static List<String> plan(UserProfileSnapshot profile) {
        Set<String> keywords = new LinkedHashSet<>();

        String title = TextKit.trim(profile.targetTitle());
        if (!title.isEmpty()) {
            String core = stripNoise(title);
            // 先放最短的方向词，例如「Java 后端开发工程师」->「后端开发」
            String direction = directionOf(core);
            if (!direction.isEmpty()) {
                keywords.add(direction);
            }
            for (String token : splitTokens(core)) {
                keywords.add(token);
            }
        }

        // 技能按用户填写的顺序取前几个，通常最能代表方向
        int skillBudget = 0;
        for (String skill : SkillLexicon.canonicalize(profile.skills())) {
            if (skillBudget++ >= 3) {
                break;
            }
            keywords.add(skill);
        }
        if (keywords.size() < 3) {
            for (String skill : SkillLexicon.extract(profile.allText())) {
                keywords.add(skill);
                if (keywords.size() >= 4) {
                    break;
                }
            }
        }

        // 兜底泛化词，保证无论如何都有词可搜
        keywords.add("后端开发");
        keywords.add("软件开发");

        return keywords.stream()
                .filter(k -> !k.isEmpty() && k.length() <= 20)
                .limit(MAX_KEYWORDS)
                .toList();
    }

    /** 从目标职位里抽出方向词，例如「Java 后端开发工程师」->「后端开发」。 */
    static String directionOf(String core) {
        for (String keyword : List.of("后端开发", "服务端开发", "后端", "服务端", "全栈", "前端开发",
                "软件开发", "研发工程师", "测试开发", "数据开发", "算法工程师", "运维开发")) {
            if (core.contains(keyword)) {
                return keyword;
            }
        }
        return "";
    }

    /** 拆出有信息量的技术词，例如「Java 后端开发」->「Java」。 */
    static List<String> splitTokens(String core) {
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : core.split("[\\s、,，/|]+")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            String canonical = SkillLexicon.canonicalOf(token);
            if (!canonical.equals(token) || isAscii(token)) {
                tokens.add(canonical);
            }
        }
        return List.copyOf(tokens);
    }

    static String stripNoise(String title) {
        String result = title;
        for (String noise : NOISE_WORDS) {
            result = result.replace(noise, " ");
        }
        return result.replaceAll("\\s+", " ").trim();
    }

    private static boolean isAscii(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }
}
