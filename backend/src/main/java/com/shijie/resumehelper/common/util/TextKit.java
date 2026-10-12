package com.shijie.resumehelper.common.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本处理工具。整个 job 模块的 core 层不依赖 Spring / Jackson，方便单元测试和离线演示。
 */
public final class TextKit {

    private static final Pattern NUMBER = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z][A-Za-z0-9+#.\\-]{1,20}");

    private TextKit() {
    }

    public static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    public static String orEmpty(String s) {
        return s == null ? "" : s;
    }

    public static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    /**
     * 归一化关键词：去掉大小写差异、空格、连字符和点。
     * "Spring Boot" / "springboot" / "spring-boot" 都会得到 "springboot"。
     */
    public static String normalize(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    /** 生成稳定 ID：同样的输入永远得到同样的 ID，用于跨数据源去重。 */
    public static String stableId(String prefix, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            sb.append(normalize(part)).append('|');
        }
        return prefix + Integer.toHexString(sb.toString().hashCode() & 0x7fffffff);
    }

    /**
     * 清洗从 PDF / DOCX 抽出的文本。
     *
     * <p>抽取出来的原文通常带一堆毛病：行尾空白、连续空行、全角空格、
     * 以及 PDF 里常见的「字 间 被 插 入 空 格」。这一步只做**不影响含义**的整理，
     * 目的是让大模型读得更准，同时让「原文对照」界面看起来不脏。
     *
     * <p>刻意保留换行结构：简历里的换行往往就是字段边界，压平了会丢信息。
     */
    public static String cleanExtractedText(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        String text = raw.replace("\r\n", "\n")
                .replace('\r', '\n')
                .replace('\u00A0', ' ')   // 不间断空格
                .replace('\u3000', ' ');  // 全角空格
        StringBuilder sb = new StringBuilder(text.length());
        boolean lastLineBlank = true;
        for (String line : text.split("\n", -1)) {
            String cleaned = line.replaceAll("[ \\t]+", " ").trim();
            if (cleaned.isEmpty()) {
                // 连续空行压成一个，且去掉开头的空行
                if (lastLineBlank) {
                    continue;
                }
                lastLineBlank = true;
            } else {
                lastLineBlank = false;
            }
            sb.append(cleaned).append('\n');
        }
        return sb.toString().trim();
    }

    /** 统计文本里非空白字符的数量。用于「抽出来的是不是空的」这类判断。 */
    public static int countVisibleChars(String text) {
        if (text == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (!Character.isWhitespace(text.charAt(i))) {
                count++;
            }
        }
        return count;
    }

    public static String truncate(String s, int max) {
        String v = orEmpty(s);
        return v.length() <= max ? v : v.substring(0, max) + "…";
    }

    /**
     * 解析薪资区间，统一换算为「千元/月」。
     * 支持："15-22K"、"15–22k"、"1.5-2.5万"、"20万/年"、"面议"。
     *
     * @return {min, max}；无法解析时返回 {-1, -1}
     */
    public static int[] parseSalary(String salary) {
        String text = orEmpty(salary);
        if (text.isBlank() || text.contains("面议") || text.contains("薪资面议")) {
            return new int[]{-1, -1};
        }
        List<Double> numbers = new ArrayList<>();
        Matcher m = NUMBER.matcher(text);
        while (m.find()) {
            numbers.add(Double.parseDouble(m.group()));
        }
        if (numbers.isEmpty()) {
            return new int[]{-1, -1};
        }
        double factor = 1.0;
        if (text.contains("万")) {
            // 月薪 2 万 -> 20 千；年薪 24 万 -> 20 千/月
            factor = text.contains("年") ? 10.0 / 12.0 : 10.0;
        }
        int min = (int) Math.round(numbers.get(0) * factor);
        int max = numbers.size() > 1 ? (int) Math.round(numbers.get(1) * factor) : min;
        if (min > max) {
            int tmp = min;
            min = max;
            max = tmp;
        }
        return new int[]{min, max};
    }

    /**
     * 从经验要求文本解析最低年限。
     * "应届 / 1 年以内" -> 0；"1-3 年" -> 1；"3年以上" -> 3；"经验不限" -> 0。
     */
    public static int parseMinYears(String experience) {
        String text = orEmpty(experience);
        if (text.isBlank() || text.contains("不限") || text.contains("应届") || text.contains("在校")
                || text.contains("实习") || text.contains("无经验")) {
            return 0;
        }
        Matcher m = NUMBER.matcher(text);
        if (m.find()) {
            return (int) Double.parseDouble(m.group());
        }
        return 0;
    }

    /** 判断岗位是否为校招/应届友好岗位。 */
    public static boolean isCampusFriendly(String experience, String jobType) {
        String text = orEmpty(experience) + " " + orEmpty(jobType);
        return text.contains("应届") || text.contains("校招") || text.contains("在校") || text.contains("实习");
    }

    /** 解析简单 CSV 行，支持双引号包裹与 "" 转义。 */
    public static List<String> splitCsvLine(String line) {
        List<String> cells = new ArrayList<>();
        if (line == null) {
            return cells;
        }
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        cell.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                cells.add(cell.toString().trim());
                cell.setLength(0);
            } else {
                cell.append(c);
            }
        }
        cells.add(cell.toString().trim());
        return cells;
    }

    /** 从自由文本中抽取疑似技术名词，用于「粘贴 JD」时兜底识别技能。 */
    public static List<String> extractLatinTerms(String text) {
        List<String> terms = new ArrayList<>();
        Matcher m = LATIN_WORD.matcher(orEmpty(text));
        while (m.find()) {
            String term = m.group();
            String norm = normalize(term);
            if (norm.length() >= 2 && !terms.contains(term)) {
                terms.add(term);
            }
        }
        return terms;
    }

    public static String joinNonBlank(String delimiter, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (!isBlank(part)) {
                if (sb.length() > 0) {
                    sb.append(delimiter);
                }
                sb.append(part.trim());
            }
        }
        return sb.toString();
    }

    public static String lower(String s) {
        return orEmpty(s).toLowerCase(Locale.ROOT);
    }

    /**
     * 字符二元组 Dice 相似度，用于中文/英文混合的短文本比较
     * （例如「Java 后端开发工程师」与「后端研发工程师（校招）」）。
     *
     * @return 0.0 ~ 1.0
     */
    public static double dice(String a, String b) {
        String x = normalize(a);
        String y = normalize(b);
        if (x.isEmpty() || y.isEmpty()) {
            return 0.0;
        }
        if (x.equals(y)) {
            return 1.0;
        }
        if (x.contains(y) || y.contains(x)) {
            return Math.max(0.85, 2.0 * Math.min(x.length(), y.length()) / (x.length() + y.length()));
        }
        List<String> gramsX = bigrams(x);
        List<String> gramsY = bigrams(y);
        int intersection = 0;
        List<String> remaining = new ArrayList<>(gramsY);
        for (String gram : gramsX) {
            if (remaining.remove(gram)) {
                intersection++;
            }
        }
        return 2.0 * intersection / (gramsX.size() + gramsY.size());
    }

    private static List<String> bigrams(String s) {
        List<String> grams = new ArrayList<>();
        if (s.length() == 1) {
            grams.add(s);
            return grams;
        }
        for (int i = 0; i + 1 < s.length(); i++) {
            grams.add(s.substring(i, i + 2));
        }
        return grams;
    }

    /** 学历等级：不限 0 < 大专 1 < 本科 2 < 硕士 3 < 博士 4。 */
    public static int educationRank(String education) {
        String text = orEmpty(education);
        if (text.contains("博士")) {
            return 4;
        }
        if (text.contains("硕士") || text.contains("研究生") || text.contains("MBA")) {
            return 3;
        }
        if (text.contains("本科") || text.contains("学士")) {
            return 2;
        }
        if (text.contains("大专") || text.contains("专科") || text.contains("高职")) {
            return 1;
        }
        return 0;
    }
}
