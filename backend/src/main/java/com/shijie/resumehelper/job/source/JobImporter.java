package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.match.SkillLexicon;
import com.shijie.resumehelper.job.util.TextKit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 粘贴岗位描述导入（对应接口草案的 {@code POST /api/jobs/import}）。
 *
 * <p>产品方案的兜底要求：「提供粘贴岗位描述的入口，让用户即使没有爬虫数据也能完成匹配」。
 * 这里用规则抽取结构化字段，完整原文保留在 description 里，
 * 所以即使标题/公司没抽准，技能匹配和差距分析依然有效。
 *
 * <p>纯 JDK 实现，不依赖大模型；需要 AI 补全时由服务层另外调用。
 */
public final class JobImporter {

    /** 手动导入的岗位统一用这个来源名，方便前端标注「用户粘贴」。 */
    public static final String SOURCE_NAME = "手动导入";

    private static final Pattern SALARY_PATTERN = Pattern.compile(
            "\\d+(?:\\.\\d+)?\\s*[Kk千]?\\s*(?:[-–—~～至到]\\s*\\d+(?:\\.\\d+)?\\s*[Kk千万]?)?\\s*(?:[Kk千万]|元)\\s*(?:/\\s*[月天日]|·\\s*\\d+薪)?");

    private static final Pattern DISTRICT_PATTERN = Pattern.compile("([\\u4e00-\\u9fa5]{1,6}(?:区|县|新区))");

    private static final Pattern TITLE_PATTERN = Pattern.compile("(?:职位|岗位|职务)(?:名称)?\\s*[:：]\\s*(.+)");
    private static final Pattern COMPANY_PATTERN = Pattern.compile("(?:公司|企业)(?:名称|全称)?\\s*[:：]\\s*(.+)");
    private static final Pattern SALARY_LABEL_PATTERN = Pattern.compile("(?:薪资|月薪|待遇|薪水|薪酬)(?:范围)?\\s*[:：]\\s*(.+)");

    private static final List<String> CITIES = List.of(
            "北京", "上海", "广州", "深圳", "杭州", "南京", "成都", "武汉", "西安", "苏州",
            "天津", "重庆", "长沙", "郑州", "青岛", "合肥", "厦门", "福州", "济南", "大连",
            "宁波", "无锡", "珠海", "东莞", "佛山", "沈阳", "哈尔滨", "昆明", "贵阳", "南昌",
            "石家庄", "太原", "兰州", "海口", "南宁", "温州", "嘉兴", "绍兴", "常州", "徐州",
            "烟台", "泉州", "金华", "惠州", "中山", "汕头", "桂林", "三亚", "远程");

    /** 已知城市列表，供智能体规划检索条件和前端筛选下拉使用。 */
    public static List<String> knownCities() {
        return CITIES;
    }

    /**
     * 把一段粘贴的岗位描述解析成岗位对象。
     *
     * @param rawText   用户粘贴的 JD 原文，不能为空
     * @param sourceUrl 原始链接，可为空
     */
    public Job parse(String rawText, String sourceUrl) {
        if (TextKit.isBlank(rawText)) {
            throw new IllegalArgumentException("岗位描述不能为空");
        }
        List<String> lines = rawText.lines().map(String::trim).filter(s -> !s.isEmpty()).toList();

        String title = firstMatch(TITLE_PATTERN, rawText);
        if (TextKit.isBlank(title) && !lines.isEmpty()) {
            title = TextKit.truncate(lines.get(0), 40);
        }
        String company = firstMatch(COMPANY_PATTERN, rawText);
        String salary = firstMatch(SALARY_LABEL_PATTERN, rawText);
        if (TextKit.isBlank(salary)) {
            Matcher m = SALARY_PATTERN.matcher(rawText);
            if (m.find()) {
                salary = m.group().trim();
            }
        }
        String head = lines.isEmpty() ? "" : String.join(" ", lines.subList(0, Math.min(6, lines.size())));
        String city = detectCity(rawText);
        if (city.isEmpty()) {
            city = detectCity(head);
        }
        String district = detectDistrict(rawText, city);

        Set<String> skills = new LinkedHashSet<>(SkillLexicon.extract(rawText));

        return Job.builder()
                .title(TextKit.isBlank(title) ? "手动导入岗位" : title)
                .company(TextKit.isBlank(company) ? "手动导入" : company)
                .city(city)
                .district(district)
                .salary(salary)
                .education(detectEducation(rawText))
                .experience(detectExperience(rawText))
                .jobType(detectJobType(rawText))
                .skills(new ArrayList<>(skills))
                .description(rawText)
                .source(SOURCE_NAME, sourceUrl)
                .collectedAt(Instant.now())
                .build();
    }

    private static String detectCity(String text) {
        for (String city : CITIES) {
            if (text.contains(city)) {
                return city;
            }
        }
        return "";
    }

    /**
     * 抽取行政区。
     * 「工作地点：上海徐汇区」这类文本里，正则会把城市名一起吃进来，
     * 所以匹配到之后要把已识别的城市前缀去掉，否则前端会显示成「上海 · 上海徐汇区」。
     */
    private static String detectDistrict(String text, String city) {
        Matcher m = DISTRICT_PATTERN.matcher(text);
        while (m.find()) {
            String candidate = m.group(1);
            if (!city.isEmpty() && candidate.startsWith(city) && candidate.length() > city.length()) {
                candidate = candidate.substring(city.length());
            }
            if ((candidate.endsWith("区") || candidate.endsWith("县")) && candidate.length() >= 2) {
                return candidate;
            }
        }
        return "";
    }

    private static String detectEducation(String text) {
        if (text.contains("博士")) {
            return "博士";
        }
        if (text.contains("硕士") || text.contains("研究生")) {
            return "硕士";
        }
        if (text.contains("本科")) {
            return "本科";
        }
        if (text.contains("大专") || text.contains("专科")) {
            return "大专";
        }
        return "不限";
    }

    private static String detectExperience(String text) {
        if (text.contains("应届") || text.contains("校招")) {
            if (text.contains("1 年以内") || text.contains("1年以内")) {
                return "应届 / 1 年以内";
            }
            return "应届";
        }
        if (text.contains("实习")) {
            return "在校 / 实习";
        }
        Matcher m = Pattern.compile("(\\d+)\\s*[-–—~～至到]\\s*(\\d+)\\s*年").matcher(text);
        if (m.find()) {
            return m.group(1) + "-" + m.group(2) + " 年";
        }
        Matcher single = Pattern.compile("(\\d+)\\s*年以上").matcher(text);
        if (single.find()) {
            return single.group(1) + " 年以上";
        }
        return "经验不限";
    }

    private static String detectJobType(String text) {
        if (text.contains("实习")) {
            return "实习";
        }
        if (text.contains("校招") || text.contains("应届")) {
            return "校招";
        }
        return "全职";
    }

    private static String firstMatch(Pattern pattern, String text) {
        Matcher m = pattern.matcher(text);
        if (m.find()) {
            return TextKit.truncate(m.group(1).trim(), 60);
        }
        return "";
    }
}
