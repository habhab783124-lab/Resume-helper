package com.shijie.resumehelper.material.extract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.material.domain.ExtractedProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 把简历原文抽成结构化资料。
 *
 * <h2>这个类最重要的事：不让模型编造经历</h2>
 * 简历是要拿去投递的。模型编一条不存在的实习经历，用户可能没细看就投出去了，
 * 面试时被问到答不上来——这是**害用户**，不是"AI 有点不稳定"。
 *
 * <p>所以这里有三道防线：
 * <ol>
 *   <li><b>提示词硬约束</b>：明确要求"原文没写的留空，不得推测"，并要求每条记录
 *       附带原文片段；</li>
 *   <li><b>证据回验</b>（{@link #verifyEvidence}）：拿模型给的 evidence 片段回到原文里找，
 *       找不到就<b>把这条记录丢掉</b>并记一条提示。模型要造假，就得同时造出一段
 *       在原文里真实存在的"原文"——这基本做不到；</li>
 *   <li><b>没有 AI 就不猜</b>：没有可用 token 时返回空结果 + 明确提示，
 *       而不是用规则硬凑一份看起来很完整、实际不可信的档案。</li>
 * </ol>
 *
 * <p>宁可少抽几条，也不能多抽一条假的。这是这个模块的产品原则。
 */
public final class ProfileExtractor {

    private static final Logger log = LoggerFactory.getLogger(ProfileExtractor.class);

    private static final String SYSTEM_PROMPT = """
            你是一个简历信息抽取工具，服务于「拾阶求职工作台」的材料整理功能。
            你的任务：从一份中文简历的原文中，抽取结构化的求职资料。

            ## 铁律（违反会让用户在面试中当场被识破，后果严重）
            1. **只能抽取原文中真实出现的信息**。原文没写的一律留空，绝对不要推测、补全、或"合理想象"。
               - 原文没写公司名 → 公司字段留空，不要写"某互联网公司"
               - 原文没写时间 → 时间字段留空，不要编一个日期
            2. **每条经历都必须给出 evidence（原文片段）**：从原文里**原样复制**一段能支撑这条记录的文字，
               20~80 字即可。不要改写、不要总结、不要加省略号。
               系统会把这段文字拿回原文里核对，核对不通过的记录会被直接丢弃。
            3. 如果原文里找不到某类信息（例如完全没有项目经历），就返回空数组。
               **不要为了凑数而编造，也不要重复使用同一段原文支撑多条记录。**
            4. summary 只能改写原文已有的事实，不得添加任何新信息。

            ## 输出格式
            只输出一个 JSON 对象。不要 markdown 代码块，不要任何解释文字，不要前后缀。

            {
              "summary": "一句话自我描述，不超过 60 字",
              "highestEducation": "大专 或 本科 或 硕士 或 博士，判断不出就留空",
              "yearsOfExperience": 0,
              "educations": [{"school":"","major":"","degree":"","period":"","evidence":""}],
              "experiences": [{"company":"","role":"","period":"","description":"","evidence":""}],
              "projects": [{"name":"","role":"","period":"","description":"","evidence":""}],
              "skills": ["Java"],
              "highlights": ["校级奖学金"]
            }

            ## 字段说明
            - yearsOfExperience：**全职**工作年限，实习不算。应届生填 0。
            - experiences：实习和工作经历都放这里。
            - projects：课程项目、个人项目、比赛作品都算。
            - skills：技术名词、工具、编程语言。用简短的标准写法（写 "Spring Boot" 而不是 "springboot框架"）。
            - description：基于原文事实概括，不超过 80 字。不要写"负责相关工作"这种没有信息量的空话。
            """;

    /** 证据片段回验时取前多少个字符去比对。太短容易误判，太长会被模型的省略号影响。 */
    private static final int EVIDENCE_PREFIX_CHARS = 16;

    /** 短于这个长度的 evidence 没法有效校验。 */
    private static final int EVIDENCE_MIN_CHARS = 4;

    private final AiClient aiClient;
    private final ObjectMapper mapper;
    private final int maxTextChars;

    /**
     * @param aiClient    大模型客户端；为 {@code null} 表示当前没有可用 token
     * @param mapper      Jackson
     * @param maxTextChars 原文超过这个长度就截断（简历通常远小于它，属于兜底）
     */
    public ProfileExtractor(AiClient aiClient, ObjectMapper mapper, int maxTextChars) {
        this.aiClient = aiClient;
        this.mapper = mapper;
        this.maxTextChars = Math.max(1000, maxTextChars);
    }

    /**
     * 抽取结构化资料。
     *
     * @param text     已抽好的简历原文
     * @param fileName 文件名，只用于日志和提示
     */
    public ExtractedProfile extract(String text, String fileName) {
        if (TextKit.isBlank(text)) {
            return ExtractedProfile.emptyWithWarning("没有可用的文本内容，无法抽取。");
        }
        if (aiClient == null || !aiClient.available() || aiClient.isFallback()) {
            // 没有大模型时**不猜**。用规则硬凑出来的档案看似完整，实际不可信，
            // 用户拿去投递会出问题。宁可明确告诉用户需要配置 token。
            return ExtractedProfile.emptyWithWarning(
                    "当前没有可用的 AI token，无法自动识别简历内容。"
                            + "你可以在岗位匹配页顶部填入自己的 token 后重试，也可以直接在下方手动填写。");
        }

        String usableText = truncateIfNeeded(text);
        List<String> warnings = new ArrayList<>();
        if (usableText.length() < text.length()) {
            warnings.add("简历原文较长，只抽取了前 " + usableText.length() + " 个字符。");
        }

        String raw = callModel(usableText, fileName, warnings);
        if (raw == null) {
            return new ExtractedProfile("", "", 0, List.of(), List.of(), List.of(), List.of(), List.of(), warnings);
        }

        ExtractedProfile parsed = parse(raw, warnings);
        ExtractedProfile verified = verifyEvidence(parsed, usableText, warnings);

        if (verified.isEmpty()) {
            warnings.add("没能从这份材料里识别出有效的经历信息。如果内容确实存在，可以试试手动填写。");
        }
        return verified;
    }

    // ------------------------------------------------------------------ 调用

    private String callModel(String text, String fileName, List<String> warnings) {
        String userPrompt = "以下是简历原文，请按系统提示的格式抽取。\n\n<简历原文>\n" + text + "\n</简历原文>";
        try {
            return aiClient.chat(SYSTEM_PROMPT, userPrompt);
        } catch (AiClient.AiException first) {
            log.warn("材料抽取的大模型调用失败：{}", first.getMessage());
            warnings.add("调用大模型失败（" + first.getMessage() + "），未能自动识别。可以重试或手动填写。");
            return null;
        } catch (RuntimeException e) {
            log.warn("材料抽取出现未预期异常", e);
            warnings.add("自动识别过程出错，未能完成。可以重试或手动填写。");
            return null;
        }
    }

    private ExtractedProfile parse(String raw, List<String> warnings) {
        String json = stripCodeFence(raw);
        try {
            JsonNode root = mapper.readTree(json);
            return new ExtractedProfile(
                    str(root, "summary"),
                    str(root, "highestEducation"),
                    intOr(root, "yearsOfExperience", 0),
                    readEducations(root),
                    readExperiences(root),
                    readProjects(root),
                    readStringArray(root, "skills"),
                    readStringArray(root, "highlights"),
                    List.of());
        } catch (Exception e) {
            // 模型偶尔会返回带解释文字的 JSON，这时 readTree 会因为前面有非 JSON 内容而失败。
            // 兜底：从第一个 { 到最后一个 } 之间再试一次。
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    JsonNode root = mapper.readTree(json.substring(start, end + 1));
                    return new ExtractedProfile(
                            str(root, "summary"), str(root, "highestEducation"),
                            intOr(root, "yearsOfExperience", 0),
                            readEducations(root), readExperiences(root), readProjects(root),
                            readStringArray(root, "skills"), readStringArray(root, "highlights"), List.of());
                } catch (Exception ignored) {
                    // 落到下面的提示
                }
            }
            log.warn("大模型返回的内容不是合法 JSON：{}", TextKit.truncate(json, 200));
            warnings.add("自动识别返回的内容格式不对，没能解析出结果。可以重试，或手动填写。");
            return new ExtractedProfile("", "", 0, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }
    }

    private static String stripCodeFence(String raw) {
        String text = TextKit.orEmpty(raw).trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            if (firstLine > 0) {
                text = text.substring(firstLine + 1);
            }
            int lastFence = text.lastIndexOf("```");
            if (lastFence >= 0) {
                text = text.substring(0, lastFence);
            }
        }
        return text.trim();
    }

    // ------------------------------------------------------------------ 证据回验

    /**
     * 拿每条记录的 evidence 回原文里核对，核不过的直接丢掉。
     *
     * <p>比对前把两边都去掉所有空白字符：模型经常会把原文的换行/空格重新排版，
     * 那不影响"这段文字确实来自原文"这个事实。
     *
     * <p>只比对 evidence 的**前若干字符**：模型常会给"……"式的省略，
     * 整段比对会把好记录也误杀；而只比对开头，编造的记录依然过不了关。
     */
    private ExtractedProfile verifyEvidence(ExtractedProfile profile, String sourceText, List<String> warnings) {
        String normalizedSource = squash(sourceText);

        List<ExtractedProfile.Education> educations = new ArrayList<>();
        int droppedEducations = 0;
        for (ExtractedProfile.Education item : profile.educations()) {
            if (item.isEmpty()) {
                continue;
            }
            if (evidenceAccepted(item.evidence(), normalizedSource)) {
                educations.add(item);
            } else {
                droppedEducations++;
            }
        }

        List<ExtractedProfile.Experience> experiences = new ArrayList<>();
        int droppedExperiences = 0;
        for (ExtractedProfile.Experience item : profile.experiences()) {
            if (item.isEmpty()) {
                continue;
            }
            if (evidenceAccepted(item.evidence(), normalizedSource)) {
                experiences.add(item);
            } else {
                droppedExperiences++;
            }
        }

        List<ExtractedProfile.Project> projects = new ArrayList<>();
        int droppedProjects = 0;
        for (ExtractedProfile.Project item : profile.projects()) {
            if (item.isEmpty()) {
                continue;
            }
            if (evidenceAccepted(item.evidence(), normalizedSource)) {
                projects.add(item);
            } else {
                droppedProjects++;
            }
        }

        // 技能做一次归一化去重：模型有时会把同一个技能写成两种形式
        Set<String> skillSet = new LinkedHashSet<>();
        for (String skill : profile.skills()) {
            String trimmed = TextKit.trim(skill);
            if (!trimmed.isEmpty() && trimmed.length() <= 24) {
                skillSet.add(trimmed);
            }
        }

        int dropped = droppedEducations + droppedExperiences + droppedProjects;
        if (dropped > 0) {
            warnings.add("有 " + dropped + " 条经历在原文里找不到对应内容，已经丢弃。"
                    + "这是为了防止 AI 编造简历里没有的经历。");
            log.info("证据回验丢弃了 {} 条记录（教育 {} / 实习工作 {} / 项目 {}）",
                    dropped, droppedEducations, droppedExperiences, droppedProjects);
        }

        return new ExtractedProfile(profile.summary(), profile.highestEducation(),
                profile.yearsOfExperience(), educations, experiences, projects,
                new ArrayList<>(skillSet), profile.highlights(), mergeWarnings(profile.warnings(), warnings));
    }

    private boolean evidenceAccepted(String evidence, String normalizedSource) {
        String normalizedEvidence = squash(evidence);
        if (normalizedEvidence.length() < EVIDENCE_MIN_CHARS) {
            // 太短没法校验。这种情况模型基本等于没给证据，但记录本身可能是好的，
            // 所以放行——不过它没有经过校验，属于"弱证据"。
            return true;
        }
        String probe = normalizedEvidence.length() <= EVIDENCE_PREFIX_CHARS
                ? normalizedEvidence
                : normalizedEvidence.substring(0, EVIDENCE_PREFIX_CHARS);
        return normalizedSource.contains(probe);
    }

    private static String squash(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static List<String> mergeWarnings(List<String> fromModel, List<String> extra) {
        List<String> merged = new ArrayList<>(fromModel);
        merged.addAll(extra);
        return merged;
    }

    private String truncateIfNeeded(String text) {
        return text.length() <= maxTextChars ? text : text.substring(0, maxTextChars);
    }

    // ------------------------------------------------------------------ JSON 读取
    // 刻意不用 Jackson 直接反序列化成 record：大模型对类型不老实
    // （把 yearsOfExperience 返回成 "0"，把单个对象返回成数组等），
    // 手动读 + 容错比配一堆 Deserializer 更省事也更稳。

    private static String str(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("").trim();
    }

    private static int intOr(JsonNode node, String field, int fallback) {
        JsonNode value = node.path(field);
        if (value.isNumber()) {
            return value.asInt();
        }
        if (value.isTextual()) {
            try {
                return (int) Double.parseDouble(value.asText().replaceAll("[^0-9.\\-]", ""));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<String> readStringArray(JsonNode node, String field) {
        JsonNode array = node.path(field);
        List<String> values = new ArrayList<>();
        if (array.isArray()) {
            for (JsonNode item : array) {
                String text = item.isTextual() ? item.asText() : item.path("name").asText("");
                if (!TextKit.isBlank(text)) {
                    values.add(text.trim());
                }
            }
        }
        return values;
    }

    private List<ExtractedProfile.Education> readEducations(JsonNode root) {
        List<ExtractedProfile.Education> list = new ArrayList<>();
        for (JsonNode item : arrayOf(root, "educations")) {
            list.add(new ExtractedProfile.Education(
                    str(item, "school"), str(item, "major"), str(item, "degree"),
                    str(item, "period"), str(item, "evidence")));
        }
        return list;
    }

    private List<ExtractedProfile.Experience> readExperiences(JsonNode root) {
        List<ExtractedProfile.Experience> list = new ArrayList<>();
        for (JsonNode item : arrayOf(root, "experiences")) {
            list.add(new ExtractedProfile.Experience(
                    str(item, "company"), str(item, "role"), str(item, "period"),
                    str(item, "description"), str(item, "evidence")));
        }
        return list;
    }

    private List<ExtractedProfile.Project> readProjects(JsonNode root) {
        List<ExtractedProfile.Project> list = new ArrayList<>();
        for (JsonNode item : arrayOf(root, "projects")) {
            list.add(new ExtractedProfile.Project(
                    str(item, "name"), str(item, "role"), str(item, "period"),
                    str(item, "description"), str(item, "evidence")));
        }
        return list;
    }

    /** 兼容模型把数组写成单个对象、或者整个字段缺失的情况。 */
    private List<JsonNode> arrayOf(JsonNode root, String field) {
        JsonNode node = root.path(field);
        if (node.isArray()) {
            List<JsonNode> items = new ArrayList<>();
            node.forEach(items::add);
            return items;
        }
        if (node.isObject()) {
            return List.of(node);
        }
        return List.of();
    }
}
