package com.shijie.resumehelper.job.domain;

import com.shijie.resumehelper.job.util.TextKit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 岗位实体。
 * <p>字段严格对齐产品方案 3.C 的岗位字段清单（职位名称、公司名称、城市、薪资、学历、经验、技能、
 * 岗位描述、来源名称、原始链接、采集时间），并额外保留结构化的薪资/年限区间用于筛选。
 * <p>纯 JDK record，core 层不依赖 Spring / Jackson。
 */
public record Job(
        String id,
        String title,
        String company,
        String city,
        String district,
        String salary,
        Integer salaryMin,
        Integer salaryMax,
        String education,
        String experience,
        Integer minYears,
        String jobType,
        boolean campus,
        List<String> skills,
        String description,
        String sourceName,
        String sourceUrl,
        Instant collectedAt) {

    public Job {
        if (TextKit.isBlank(title)) {
            throw new IllegalArgumentException("岗位名称不能为空");
        }
        company = TextKit.isBlank(company) ? "未知公司" : company.trim();
        city = TextKit.orEmpty(city).trim();
        district = TextKit.orEmpty(district).trim();
        salary = TextKit.isBlank(salary) ? "面议" : salary.trim();

        // 薪资区间：调用方没算就在构造时兜底解析
        if (salaryMin == null || salaryMax == null) {
            int[] parsed = TextKit.parseSalary(salary);
            salaryMin = salaryMin == null ? parsed[0] : salaryMin;
            salaryMax = salaryMax == null ? parsed[1] : salaryMax;
        }
        education = TextKit.isBlank(education) ? "不限" : education.trim();
        experience = TextKit.isBlank(experience) ? "经验不限" : experience.trim();
        minYears = minYears == null ? TextKit.parseMinYears(experience) : minYears;
        jobType = TextKit.isBlank(jobType) ? "全职" : jobType.trim();
        campus = campus || TextKit.isCampusFriendly(experience, jobType);
        skills = skills == null ? List.of() : List.copyOf(skills);
        description = TextKit.orEmpty(description);
        sourceName = TextKit.isBlank(sourceName) ? "未知来源" : sourceName.trim();
        sourceUrl = TextKit.orEmpty(sourceUrl).trim();
        collectedAt = collectedAt == null ? Instant.now() : collectedAt;
        id = TextKit.isBlank(id) ? TextKit.stableId("job-", company, title, city) : id;
    }

    /** 前端岗位卡片展示的地点，如「上海 · 徐汇」。 */
    public String place() {
        return TextKit.joinNonBlank(" · ", city, district);
    }

    /** 前端岗位卡片的公司首字标记。 */
    public String companyMark() {
        return company.isEmpty() ? "?" : company.substring(0, 1);
    }

    /** 是否与给定城市匹配；岗位城市为空时视为「不限」，不算冲突。 */
    public boolean cityMatches(List<String> targetCities) {
        if (targetCities == null || targetCities.isEmpty() || city.isEmpty()) {
            return true;
        }
        for (String target : targetCities) {
            if (!TextKit.isBlank(target) && (city.contains(target.trim()) || target.trim().contains(city))) {
                return true;
            }
        }
        return false;
    }

    /** 用于跨数据源去重的业务指纹：同一公司在同一城市的同名岗位视为同一岗位。 */
    public String fingerprint() {
        return TextKit.normalize(company) + "#" + TextKit.normalize(title) + "#" + TextKit.normalize(city);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        Builder b = new Builder();
        b.id = id;
        b.title = title;
        b.company = company;
        b.city = city;
        b.district = district;
        b.salary = salary;
        b.salaryMin = salaryMin;
        b.salaryMax = salaryMax;
        b.education = education;
        b.experience = experience;
        b.minYears = minYears;
        b.jobType = jobType;
        b.campus = campus;
        b.skills = new ArrayList<>(skills);
        b.description = description;
        b.sourceName = sourceName;
        b.sourceUrl = sourceUrl;
        b.collectedAt = collectedAt;
        return b;
    }

    /** 手写 Builder：字段较多，避免构造器参数顺序出错。 */
    public static final class Builder {
        private String id;
        private String title;
        private String company;
        private String city;
        private String district;
        private String salary;
        private Integer salaryMin;
        private Integer salaryMax;
        private String education;
        private String experience;
        private Integer minYears;
        private String jobType;
        private boolean campus;
        private List<String> skills = new ArrayList<>();
        private String description;
        private String sourceName;
        private String sourceUrl;
        private Instant collectedAt;

        public Builder id(String v) {
            this.id = v;
            return this;
        }

        public Builder title(String v) {
            this.title = v;
            return this;
        }

        public Builder company(String v) {
            this.company = v;
            return this;
        }

        public Builder city(String v) {
            this.city = v;
            return this;
        }

        public Builder district(String v) {
            this.district = v;
            return this;
        }

        public Builder salary(String v) {
            this.salary = v;
            return this;
        }

        public Builder salaryRange(Integer min, Integer max) {
            this.salaryMin = min;
            this.salaryMax = max;
            return this;
        }

        public Builder education(String v) {
            this.education = v;
            return this;
        }

        public Builder experience(String v) {
            this.experience = v;
            return this;
        }

        public Builder minYears(Integer v) {
            this.minYears = v;
            return this;
        }

        public Builder jobType(String v) {
            this.jobType = v;
            return this;
        }

        public Builder campus(boolean v) {
            this.campus = v;
            return this;
        }

        public Builder skills(List<String> v) {
            this.skills = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public Builder skills(String... v) {
            this.skills = new ArrayList<>(List.of(v));
            return this;
        }

        public Builder description(String v) {
            this.description = v;
            return this;
        }

        public Builder source(String name, String url) {
            this.sourceName = name;
            this.sourceUrl = url;
            return this;
        }

        public Builder collectedAt(Instant v) {
            this.collectedAt = v;
            return this;
        }

        public Job build() {
            return new Job(id, title, company, city, district, salary, salaryMin, salaryMax, education,
                    experience, minYears, jobType, campus, skills, description, sourceName, sourceUrl, collectedAt);
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        return o instanceof Job other && Objects.equals(id, other.id);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(id);
    }
}
