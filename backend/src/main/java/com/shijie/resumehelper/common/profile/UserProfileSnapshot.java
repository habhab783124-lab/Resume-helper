package com.shijie.resumehelper.common.profile;

import com.shijie.resumehelper.common.util.TextKit;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户求职画像快照。
 *
 * <p><b>跨模块共享的数据结构</b>，所以放在 {@code common.profile} 而不是某个业务模块下：
 * <ul>
 *   <li>材料模块（功能一）<b>产出</b>它：上传简历 → 抽取 → 用户确认 → 存成画像；</li>
 *   <li>岗位模块（功能二）<b>只读消费</b>它，用于匹配打分与差距分析；</li>
 *   <li>简历与面试模块以后也会读它。</li>
 * </ul>
 * 字段对应产品方案 7 的 Profile：教育、经历、技能和求职偏好。
 *
 * <p><b>联系方式（姓名 / 电话 / 邮箱）单独放在最前面</b>：它们只用于简历抬头，
 * <b>不参与匹配打分</b>。之前的模型里没有这三项，导致生成的简历抬头只能显示
 * 登录会话里写死的"林同学"，也没有电话邮箱——拿去投递是不完整的。
 */
public record UserProfileSnapshot(
        String userId,
        // ---- 联系方式：只出现在简历抬头，不参与匹配 ----
        String name,
        String phone,
        String email,
        // ---- 求职偏好与经历：参与匹配打分 ----
        String targetTitle,
        List<String> targetCities,
        Integer yearsOfExperience,
        String education,
        List<String> educations,
        List<String> skills,
        List<String> industries,
        String expectedSalary,
        String summary,
        List<String> experiences) {

    public UserProfileSnapshot {
        userId = TextKit.orEmpty(userId);
        name = TextKit.trim(name);
        phone = TextKit.trim(phone);
        email = TextKit.trim(email);
        targetTitle = TextKit.trim(targetTitle);
        targetCities = targetCities == null ? List.of() : List.copyOf(targetCities);
        yearsOfExperience = yearsOfExperience == null ? 0 : yearsOfExperience;
        education = TextKit.trim(education);
        educations = educations == null ? List.of() : List.copyOf(educations);
        skills = skills == null ? List.of() : List.copyOf(skills);
        industries = industries == null ? List.of() : List.copyOf(industries);
        expectedSalary = TextKit.trim(expectedSalary);
        summary = TextKit.orEmpty(summary);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
    }

    /** 有没有填联系方式——简历页据此提示"抬头还缺信息"。 */
    public boolean hasContact() {
        return !phone.isEmpty() || !email.isEmpty();
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
        private String name;
        private String phone;
        private String email;
        private String targetTitle;
        private List<String> targetCities = new ArrayList<>();
        private Integer yearsOfExperience;
        private String education;
        private List<String> educations = new ArrayList<>();
        private List<String> skills = new ArrayList<>();
        private List<String> industries = new ArrayList<>();
        private String expectedSalary;
        private String summary;
        private List<String> experiences = new ArrayList<>();

        public Builder userId(String v) {
            this.userId = v;
            return this;
        }

        /** 姓名，只用于简历抬头。 */
        public Builder name(String v) {
            this.name = v;
            return this;
        }

        /** 联系电话，只用于简历抬头。 */
        public Builder phone(String v) {
            this.phone = v;
            return this;
        }

        /** 联系邮箱，只用于简历抬头。 */
        public Builder email(String v) {
            this.email = v;
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

        /**
         * 教育经历，每条一行（例如「上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06」）。
         *
         * <p>和 {@code education}（只有学历层次）分开：学历是岗位匹配的一个打分维度，
         * 而教育经历要展示在个人资料页上，需要学校 / 专业 / 时间。
         */
        public Builder educations(List<String> v) {
            this.educations = v == null ? new ArrayList<>() : new ArrayList<>(v);
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
            return new UserProfileSnapshot(userId, name, phone, email, targetTitle, targetCities,
                    yearsOfExperience, education, educations, skills, industries,
                    expectedSalary, summary, experiences);
        }
    }
}
