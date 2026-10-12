package com.shijie.resumehelper.job.domain;

import com.shijie.resumehelper.job.util.TextKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 岗位检索条件。对应产品方案 3.C 的筛选维度：
 * 「城市、工作类型和最低年限作为筛选条件；技能、经历和行业方向作为匹配维度」。
 */
public final class JobQuery {

    // 字段给空串默认值：查询对象会被 builder 逐步填充，
    // 未设置的字段如果留 null，任何 `.isEmpty()` / `.isBlank()` 调用都会 NPE。
    private String keyword = "";
    private String city = "";
    private String jobType = "";
    private Integer maxMinYears;
    private Integer salaryMin;
    private boolean campusOnly;
    /** 是否把城市当硬筛选条件。默认 false：城市作为匹配维度打分，让用户看到更多岗位和差距。 */
    private boolean strictCity;
    private final List<String> keywords = new ArrayList<>();
    private int limit = 30;

    public JobQuery() {
    }

    public static JobQuery of(String keyword) {
        return new JobQuery().keyword(keyword);
    }

    public String keyword() {
        return keyword;
    }

    public JobQuery keyword(String v) {
        this.keyword = TextKit.trim(v);
        return this;
    }

    public String city() {
        return city;
    }

    public JobQuery city(String v) {
        this.city = TextKit.trim(v);
        return this;
    }

    public String jobType() {
        return jobType;
    }

    public JobQuery jobType(String v) {
        this.jobType = TextKit.trim(v);
        return this;
    }

    public Integer maxMinYears() {
        return maxMinYears;
    }

    public JobQuery maxMinYears(Integer v) {
        this.maxMinYears = v;
        return this;
    }

    public Integer salaryMin() {
        return salaryMin;
    }

    public JobQuery salaryMin(Integer v) {
        this.salaryMin = v;
        return this;
    }

    public boolean campusOnly() {
        return campusOnly;
    }

    public JobQuery campusOnly(boolean v) {
        this.campusOnly = v;
        return this;
    }

    public boolean strictCity() {
        return strictCity;
    }

    /** 用户在岗位页开启「城市」筛选时置为 true，交给数据源做硬过滤。 */
    public JobQuery strictCity(boolean v) {
        this.strictCity = v;
        return this;
    }

    /** 智能体在反思阶段会往这里追加同义/泛化的关键词，做多轮扩检。 */
    public List<String> keywords() {
        return keywords;
    }

    public JobQuery addKeyword(String v) {
        if (!TextKit.isBlank(v) && !keywords.contains(v.trim())) {
            keywords.add(v.trim());
        }
        return this;
    }

    public int limit() {
        return limit <= 0 ? 30 : Math.min(limit, 200);
    }

    public JobQuery limit(int v) {
        this.limit = v;
        return this;
    }

    /** 供数据源使用的全部检索词：主关键词 + 扩展关键词。 */
    public List<String> allKeywords() {
        List<String> all = new ArrayList<>();
        if (!TextKit.isBlank(keyword)) {
            all.add(keyword);
        }
        for (String k : keywords) {
            if (!all.contains(k)) {
                all.add(k);
            }
        }
        return all;
    }

    /**
     * 岗位是否通过「硬筛选」条件。硬条件不通过的直接不进入候选池，
     * 匹配度只用于解释贴合程度，不表示录取概率。
     */
    public boolean accepts(Job job) {
        if (strictCity && !city.isBlank() && !job.cityMatches(List.of(city))) {
            return false;
        }
        if (jobType != null && !jobType.isBlank() && !job.jobType().contains(jobType) && !jobType.contains(job.jobType())) {
            return false;
        }
        if (campusOnly && !job.campus()) {
            return false;
        }
        if (maxMinYears != null && job.minYears() > maxMinYears) {
            return false;
        }
        if (salaryMin != null && salaryMin > 0 && job.salaryMax() > 0 && job.salaryMax() < salaryMin) {
            return false;
        }
        return true;
    }

    /** 关键词是否命中岗位文本（职位名 + 公司 + 技能 + 描述）。 */
    public boolean matchesKeyword(Job job, String kw) {
        if (TextKit.isBlank(kw)) {
            return true;
        }
        String needle = TextKit.normalize(kw);
        if (needle.isEmpty()) {
            return true;
        }
        String haystack = TextKit.normalize(TextKit.joinNonBlank(" ",
                job.title(), job.company(), job.city(), String.join(" ", job.skills()), job.description()));
        return haystack.contains(needle);
    }

    public JobQuery copy() {
        JobQuery q = new JobQuery();
        q.keyword = keyword;
        q.city = city;
        q.jobType = jobType;
        q.maxMinYears = maxMinYears;
        q.salaryMin = salaryMin;
        q.campusOnly = campusOnly;
        q.strictCity = strictCity;
        q.limit = limit;
        q.keywords.addAll(keywords);
        return q;
    }
}
