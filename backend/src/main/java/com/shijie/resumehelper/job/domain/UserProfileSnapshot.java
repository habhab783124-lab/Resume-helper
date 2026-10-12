package com.shijie.resumehelper.job.domain;

import com.shijie.resumehelper.job.util.TextKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户求职画像快照。
 * <p>由「个人资料」模块（功能一，其他人负责）产出，岗位模块只读消费，不反向依赖。
 * 字段对应产品方案 7 的 Profile：教育、经历、技能和求职偏好。
 */
public record UserProfileSnapshot(
        String userId,
        String targetTitle,
        List<String> targetCities,
        Integer yearsOfExperience,
        String education,
        List<String> skills,
        List<String> industries,
        String expectedSalary,
        String summary,
        List<String> experiences) {

    public UserProfileSnapshot {
        userId = TextKit.orEmpty(userId);
        targetTitle = TextKit.trim(targetTitle);
        targetCities = targetCities == null ? List.of() : List.copyOf(targetCities);
        yearsOfExperience = yearsOfExperience == null ? 0 : yearsOfExperience;
        education = TextKit.trim(education);
        skills = skills == null ? List.of() : List.copyOf(skills);
        industries = industries == null ? List.of() : List.copyOf(industries);
        expectedSalary = TextKit.trim(expectedSalary);
        summary = TextKit.orEmpty(summary);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
    }

    /** 画像是否信息量足够，可以跑智能体检索。 */
    public boolean isUsable() {
        return !targetTitle.isEmpty() || !skills.isEmpty() || !experiences.isEmpty() || !summary.isEmpty();
    }

    /** 把所有文本拼成一段，供关键词抽取和技能识别使用。 */
    public String allText() {
        return TextKit.joinNonBlank("\n",
                targetTitle,
                String.join(" ", skills),
                String.join(" ", industries),
                summary,
                String.join("\n", experiences));
    }

    /** 目标城市的第一个，作为默认检索城市。 */
    public String primaryCity() {
        return targetCities.isEmpty() ? "" : targetCities.get(0);
    }

    /** 期望薪资解析出的下限（千元/月），用于筛选参考。 */
    public int expectedSalaryMin() {
        return TextKit.parseSalary(expectedSalary)[0];
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String userId;
        private String targetTitle;
        private List<String> targetCities = new ArrayList<>();
        private Integer yearsOfExperience;
        private String education;
        private List<String> skills = new ArrayList<>();
        private List<String> industries = new ArrayList<>();
        private String expectedSalary;
        private String summary;
        private List<String> experiences = new ArrayList<>();

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        public Builder targetTitle(String v) {
            this.targetTitle = v;
            return this;
        }

        public Builder targetCities(List<String> v) {
            this.targetCities = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public Builder targetCities(String... v) {
            this.targetCities = new ArrayList<>(List.of(v));
            return this;
        }

        public Builder yearsOfExperience(Integer v) {
            this.yearsOfExperience = v;
            return this;
        }

        public Builder education(String v) {
            this.education = v;
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

        public Builder industries(String... v) {
            this.industries = new ArrayList<>(List.of(v));
            return this;
        }

        /** 供 DTO 转换使用。注意：不能只有 varargs 版本，否则传 List 会编译不过。 */
        public Builder industries(List<String> v) {
            this.industries = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public Builder expectedSalary(String v) {
            this.expectedSalary = v;
            return this;
        }

        public Builder summary(String v) {
            this.summary = v;
            return this;
        }

        public Builder experiences(List<String> v) {
            this.experiences = v == null ? new ArrayList<>() : new ArrayList<>(v);
            return this;
        }

        public UserProfileSnapshot build() {
            return new UserProfileSnapshot(userId, targetTitle, targetCities, yearsOfExperience, education,
                    skills, industries, expectedSalary, summary, experiences);
        }
    }
}
