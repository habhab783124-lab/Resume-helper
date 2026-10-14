package com.shijie.resumehelper.resume.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.resume.config.ResumeModuleProperties;
import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeSuggestion;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import com.shijie.resumehelper.resume.store.ResumeVersionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 简历生成的业务编排。
 *
 * <h2>三步</h2>
 * <ol>
 *   <li><b>取材</b>：把用户确认过的画像（{@link UserProfileSnapshot}）变成一份简历内容
 *       （{@link ResumeDraft}）。画像里经历是"每行一条"的字符串，这里负责拆成
 *       抬头 / 副标题 / 要点。</li>
 *   <li><b>定向</b>：可选。给了目标职位或岗位描述时，让模型对每条经历给改写建议；
 *       建议带原文和原因，<b>不自动覆盖</b>，由用户逐条采纳。</li>
 *   <li><b>存版</b>：确认后保存为一个版本，投不同岗位可以用不同版本。</li>
 * </ol>
 *
 * <h2>和材料模块一样的诚实原则</h2>
 * 模型<b>不许编造</b>原文里没有的数字和成果。简历是要投出去的，
 * 「优化了接口性能」和「把响应时间从 180ms 降到 45ms」是两回事，
 * 后者如果原文没有、模型硬编一个，用户面试时会被问穿。
 * 所以提示词要求：缺量化数据就写 {@code [补充具体数据]} 占位符，让用户自己填。
 */
public class ResumeService {

    private static final Logger log = LoggerFactory.getLogger(ResumeService.class);

    private static final String SYSTEM_PROMPT = """
            你是一位帮应届生改简历的资深工程师。用户会给你一份简历的若干条经历，
            以及他这次要投的目标岗位（可能还有岗位描述）。

            你的任务：逐条给出**表达层面**的改写建议，让经历更清楚、更有分量。

            ## 铁律（违反会让用户面试时被问穿，后果严重）
            1. **不得添加原文里没有的任何事实、数字或成果。**
               - 原文没写响应时间 → 不要写"响应时间降低 60%"
               - 原文没写用户量 → 不要写"服务 10 万用户"
               - 原文没写你独立负责 → 不要写"独立负责"
               如果原文缺少量化数据，就在建议稿里写 [!补充具体数据] 这类占位符，
               **让用户自己填**，不要替他编一个数字。
            2. **只改表达，不改事实**：动词更准确、结构更清晰、去掉"参与了相关工作"这类空话、
               把与目标岗位最相关的能力提到前面。
            3. 每条建议必须给出：
               - original：**原文照抄，一个字都不要改**（系统要用它把建议定位回原句）
               - suggested：改写后的版本
               - reason：为什么这么改（一句话，说清楚改的是表达还是结构，别吹）
            4. 如果某条经历已经写得不错，**不要硬改**，直接跳过它。

            ## 输出格式
            只输出一个 JSON 对象，不要 markdown 代码块，不要解释文字。

            {"suggestions":[{"section":"experiences","index":0,"original":"原文照抄","suggested":"改写稿","reason":"为什么"}]}

            section 取 experiences / projects / educations 之一，index 是该段的第几条（从 0 开始）。
            """;

    private final ResumeVersionStore store;
    private final ResumeModuleProperties properties;
    private final ObjectMapper mapper = new ObjectMapper();

    public ResumeService(ResumeVersionStore store, ResumeModuleProperties properties) {
        this.store = store;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ 取材

    /**
     * 把画像变成简历内容。
     *
     * <p>画像为空时返回空草稿，而不是抛异常——页面上会提示"先去整理材料"，
     * 比一个报错有用。
     */
    public ResumeDraft buildDraft(UserProfileSnapshot profile) {
        if (profile == null) {
            return new ResumeDraft("", "", List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        }

        List<ResumeDraft.Entry> educations = new ArrayList<>();
        for (String line : profile.educations()) {
            ResumeDraft.Entry entry = parseEducation(line);
            if (entry != null) {
                educations.add(entry);
            }
        }

        List<ResumeDraft.Entry> experiences = new ArrayList<>();
        for (String line : profile.experiences()) {
            ResumeDraft.Entry entry = parseExperience(line);
            if (entry != null) {
                experiences.add(entry);
            }
        }

        // 联系方式：演示阶段只有城市。电话 / 邮箱属于个人信息，让用户自己在页面上决定填不填，
        // 不要从别处"猜"一个填进去。
        List<String> contacts = new ArrayList<>(profile.targetCities());
        if (!profile.expectedSalary().isBlank()) {
            contacts.add("期望薪资 " + profile.expectedSalary());
        }

        // 关键词行放最相关的几个技能，方便 HR 扫一眼
        List<String> keywords = profile.skills().stream().limit(8).toList();

        return new ResumeDraft(
                profile.targetTitle(),
                profile.summary(),
                contacts,
                educations,
                experiences,
                List.of(),                 // 画像里没有独立的项目字段，项目经历混在 experiences 里
                profile.skills(),
                keywords);
    }

    /**
     * 解析一条教育经历：{@code 上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06}
     * → 抬头「上海理工大学」+ 副标题「本科 · 计算机科学与技术 · 2022.09—2026.06」。
     */
    static ResumeDraft.Entry parseEducation(String line) {
        String text = TextKit.trim(line);
        if (text.isEmpty()) {
            return null;
        }
        List<String> segments = splitByMiddleDot(text);
        if (segments.size() <= 1) {
            return new ResumeDraft.Entry(text, "", List.of());
        }
        String title = segments.get(0);
        String meta = String.join(" · ", segments.subList(1, segments.size()));
        return new ResumeDraft.Entry(title, meta, List.of());
    }

    /**
     * 解析一条经历：{@code 抬头 · 时间：要点1；要点2}
     *
     * <p>拆不出冒号就整行当抬头，<b>绝不丢内容</b>——宁可排版差一点，
     * 也不能让用户确认过的经历在简历里凭空少一段。
     */
    static ResumeDraft.Entry parseExperience(String line) {
        String text = TextKit.trim(line);
        if (text.isEmpty()) {
            return null;
        }
        int colon = firstColon(text);
        String head = colon > 0 ? text.substring(0, colon) : text;
        String body = colon > 0 ? text.substring(colon + 1) : "";

        String title = head;
        String meta = "";
        List<String> segments = splitByMiddleDot(head);
        // 抬头最后一段是时间范围时，拆出来当副标题（"… · 2025.07—2025.09"）
        if (segments.size() >= 2 && looksLikePeriod(segments.get(segments.size() - 1))) {
            meta = segments.get(segments.size() - 1);
            title = String.join(" · ", segments.subList(0, segments.size() - 1));
        }
        return new ResumeDraft.Entry(title, meta, splitBullets(body));
    }

    /** 第一个分隔标题与正文的冒号。要排除时间里的冒号（{@code 10:30}）。 */
    static int firstColon(String text) {
        int full = text.indexOf('：');
        if (full >= 0) {
            return full;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == ':') {
                boolean digitBefore = i > 0 && Character.isDigit(text.charAt(i - 1));
                boolean digitAfter = i + 1 < text.length() && Character.isDigit(text.charAt(i + 1));
                if (!(digitBefore && digitAfter)) {
                    return i;
                }
            }
        }
        return -1;
    }

    static List<String> splitByMiddleDot(String text) {
        return java.util.Arrays.stream(text.split("\\s*[·・]\\s*"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** 要点按分号拆。不分句号：一句话被从中间劈开会读不通。 */
    static List<String> splitBullets(String body) {
        if (body == null || body.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(body.split("[；;\\n]+"))
                .map(String::trim)
                .filter(part -> !part.isEmpty())
                .toList();
    }

    /** 看起来像时间范围：含四位年份，且含连接符或"至/到"。 */
    static boolean looksLikePeriod(String text) {
        if (text == null || !text.matches(".*\\d{4}.*")) {
            return false;
        }
        return text.contains("—") || text.contains("-") || text.contains("~")
                || text.contains("至") || text.contains("到") || text.contains("／") || text.contains("/");
    }

    // ------------------------------------------------------------------ 定向建议

    /**
     * 让模型对每条经历给出改写建议。刻意返回**建议列表**而不是直接改好的简历：
     * 产品要求「AI 建议不会自动覆盖原文」，必须由用户逐条采纳。
     *
     * @param aiClient 可以为 null（没有 token）。此时返回空列表 + 一条说明，
     *                 而不是编几条假建议出来。
     */
    public SuggestResult suggest(ResumeDraft draft, String targetTitle, String rawJd, AiClient aiClient) {
        String jd = TextKit.truncate(TextKit.orEmpty(rawJd).trim(), properties.getMaxJdChars());

        if (draft == null || draft.isEmpty()) {
            return new SuggestResult(List.of(), "简历还是空的。先去「材料整理」确认一份资料，再来生成定向内容。");
        }
        if (aiClient == null || !aiClient.available() || aiClient.isFallback()) {
            return new SuggestResult(List.of(),
                    "当前没有可用的 AI token，无法生成改写建议。可以到「岗位匹配」页顶部填入自己的 token 后重试；"
                            + "不填也不影响手动编辑和打印。");
        }

        String prompt = buildSuggestPrompt(draft, targetTitle, jd);
        String reply;
        try {
            reply = aiClient.chat(SYSTEM_PROMPT, prompt);
        } catch (AiClient.AiException e) {
            log.warn("简历建议调用大模型失败：{}", e.getMessage());
            return new SuggestResult(List.of(), "调用大模型失败（" + e.getMessage() + "），请重试。");
        } catch (RuntimeException e) {
            log.warn("简历建议出现未预期异常", e);
            return new SuggestResult(List.of(), "生成建议时出错，请重试。");
        }

        List<ResumeSuggestion> suggestions = parseSuggestions(reply, draft);
        if (suggestions.isEmpty()) {
            return new SuggestResult(List.of(), "模型这次没有给出可用的建议（可能你的经历已经写得比较清楚了）。");
        }
        return new SuggestResult(suggestions, "");
    }

    /** 结果里同时带上"为什么没有建议"，避免页面只显示一个空列表让用户摸不着头脑。 */
    public record SuggestResult(List<ResumeSuggestion> suggestions, String message) {
    }

    private String buildSuggestPrompt(ResumeDraft draft, String targetTitle, String jd) {
        StringBuilder sb = new StringBuilder();
        sb.append("目标职位：").append(TextKit.isBlank(targetTitle) ? "（未填写）" : targetTitle).append('\n');
        if (!jd.isBlank()) {
            sb.append("\n岗位描述：\n").append(jd).append('\n');
        }
        if (!draft.summary().isBlank()) {
            sb.append("\n个人简介：\n").append(draft.summary()).append('\n');
        }
        appendSection(sb, "experiences", "实习 / 工作经历", draft.experiences());
        appendSection(sb, "projects", "项目经历", draft.projects());
        appendSection(sb, "educations", "教育经历", draft.educations());
        sb.append("\n请按系统提示的 JSON 格式给出建议，最多 ").append(properties.getMaxSuggestions()).append(" 条。");
        return sb.toString();
    }

    private void appendSection(StringBuilder sb, String key, String title, List<ResumeDraft.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        sb.append("\n【").append(title).append("】（section=").append(key).append("）\n");
        for (int i = 0; i < entries.size(); i++) {
            ResumeDraft.Entry entry = entries.get(i);
            sb.append("  [").append(i).append("] ").append(entry.title());
            if (!entry.meta().isBlank()) {
                sb.append("（").append(entry.meta()).append("）");
            }
            sb.append('\n');
            for (String bullet : entry.bullets()) {
                sb.append("      - ").append(bullet).append('\n');
            }
        }
    }

    /**
     * 解析模型返回的建议。
     *
     * <p>{@code original} 必须能在简历里找到——找不到就丢掉这条。
     * 和材料模块的证据回验是同一个道理：模型如果连"原文"都是编的，
     * 那这条建议就没法让用户核对，不能展示。
     */
    private List<ResumeSuggestion> parseSuggestions(String reply, ResumeDraft draft) {
        List<ResumeSuggestion> suggestions = new ArrayList<>();
        try {
            JsonNode root = mapper.readTree(stripCodeFence(reply));
            JsonNode array = root.path("suggestions");
            if (!array.isArray()) {
                return List.of();
            }
            List<String> known = collectKnownText(draft);
            for (JsonNode node : array) {
                ResumeSuggestion suggestion = new ResumeSuggestion(
                        node.path("section").asText("experiences"),
                        node.path("index").asInt(0),
                        node.path("original").asText("").trim(),
                        node.path("suggested").asText("").trim(),
                        node.path("reason").asText("").trim());
                if (!suggestion.isUseful()) {
                    continue;
                }
                if (!known.contains(squash(suggestion.original()))) {
                    log.info("丢弃一条建议：original 在简历里找不到（{}）", TextKit.truncate(suggestion.original(), 40));
                    continue;
                }
                suggestions.add(suggestion);
                if (suggestions.size() >= properties.getMaxSuggestions()) {
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("解析简历建议失败：{}", TextKit.truncate(reply, 200));
            return List.of();
        }
        return suggestions;
    }

    /** 简历里出现过的所有文本（去掉空白后的形式），用于校验 original 是不是照抄的。 */
    private List<String> collectKnownText(ResumeDraft draft) {
        List<String> known = new ArrayList<>();
        for (String bullet : draft.allBullets()) {
            known.add(squash(bullet));
        }
        for (ResumeDraft.Entry entry : draft.educations()) {
            known.add(squash(entry.title()));
        }
        for (ResumeDraft.Entry entry : draft.experiences()) {
            known.add(squash(entry.title()));
        }
        for (ResumeDraft.Entry entry : draft.projects()) {
            known.add(squash(entry.title()));
        }
        return known;
    }

    private static String squash(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
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

    // ------------------------------------------------------------------ 版本

    public ResumeVersion saveVersion(String userId, String name, String targetTitle, String jd, ResumeDraft draft) {
        if (draft == null || draft.isEmpty()) {
            throw new IllegalArgumentException("简历是空的，没什么可保存的。先去「材料整理」确认一份资料。");
        }
        if (store.count(userId) >= properties.getMaxVersionsPerUser()) {
            throw new IllegalArgumentException("保存的版本太多了（上限 "
                    + properties.getMaxVersionsPerUser() + " 个）。请先删掉一些不用的版本。");
        }
        String id = "res-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String versionName = TextKit.isBlank(name) ? defaultName(targetTitle) : name.trim();
        return store.save(new ResumeVersion(id, userId, versionName, targetTitle,
                TextKit.truncate(TextKit.orEmpty(jd), properties.getMaxJdChars()), draft, Instant.now()));
    }

    private String defaultName(String targetTitle) {
        String stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm")
                .withZone(ZoneId.systemDefault()).format(Instant.now());
        return (TextKit.isBlank(targetTitle) ? "通用版" : targetTitle.trim()) + " · " + stamp;
    }

    public List<ResumeVersion> listVersions(String userId) {
        return store.list(userId);
    }

    public Optional<ResumeVersion> findVersion(String userId, String versionId) {
        return store.find(userId, versionId);
    }

    public boolean deleteVersion(String userId, String versionId) {
        return store.delete(userId, versionId);
    }
}
