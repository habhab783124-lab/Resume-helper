package com.shijie.resumehelper.common.profile;

import com.shijie.resumehelper.common.profile.UserProfileSnapshot;

import java.util.List;

/**
 * 前端传过来的用户求职画像。
 *
 * <p>岗位模块<b>不负责</b>产出画像：它由「个人资料 / 材料整理」模块（功能一，队友负责）
 * 解析用户上传的 PDF / DOCX / 粘贴经历后生成，确认后拼进岗位检索请求。
 * 这样两个模块可以并行开发，接口就是这份 DTO。
 *
 * <p>用可变 POJO 而不是 record，是为了让 Jackson 反序列化不依赖
 * {@code -parameters} 编译参数，队友改字段名时更不容易踩坑。
 */
public class ProfileDto {

    /** 用户标识。收藏功能需要它；没有登录体系时前端可以传固定值。 */
    private String userId;
    /** 目标职位，例如「Java 后端开发工程师」。这是最影响检索效果的一个字段。 */
    private String targetTitle;
    /** 期望城市，例如 ["上海"]。 */
    private List<String> targetCities;
    /** 相关经验年限，应届生填 0。 */
    private Integer yearsOfExperience;
    /** 学历，例如「本科」。 */
    private String education;
    /**
     * 教育经历，每条一行（学校 / 专业 / 时间）。
     *
     * <p>追加字段：岗位模块不用它，但个人资料页要按「教育经历」展示，
     * 只有 {@code education}（学历层次）填不出来。面试模块的简历驱动出题也可能用得上。
     */
    private List<String> educations;
    /** 技能列表，例如 ["Java", "Spring Boot", "MySQL"]。 */
    private List<String> skills;
    /** 目标行业。 */
    private List<String> industries;
    /** 期望薪资，例如「15-22K」。 */
    private String expectedSalary;
    /** 自我描述。 */
    private String summary;
    /** 项目 / 实习经历摘要，每条一段。用于抽取技能和生成匹配解释。 */
    private List<String> experiences;

    public UserProfileSnapshot toSnapshot() {
        return UserProfileSnapshot.builder()
                .userId(userId)
                .targetTitle(targetTitle)
                .targetCities(targetCities)
                .yearsOfExperience(yearsOfExperience)
                .education(education)
                .educations(educations)
                .skills(skills)
                .industries(industries)
                .expectedSalary(expectedSalary)
                .summary(summary)
                .experiences(experiences)
                .build();
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getTargetTitle() {
        return targetTitle;
    }

    public void setTargetTitle(String targetTitle) {
        this.targetTitle = targetTitle;
    }

    public List<String> getTargetCities() {
        return targetCities;
    }

    public void setTargetCities(List<String> targetCities) {
        this.targetCities = targetCities;
    }

    public Integer getYearsOfExperience() {
        return yearsOfExperience;
    }

    public void setYearsOfExperience(Integer yearsOfExperience) {
        this.yearsOfExperience = yearsOfExperience;
    }

    public String getEducation() {
        return education;
    }

    public void setEducation(String education) {
        this.education = education;
    }

    public List<String> getEducations() {
        return educations;
    }

    public void setEducations(List<String> educations) {
        this.educations = educations;
    }

    public List<String> getSkills() {
        return skills;
    }

    public void setSkills(List<String> skills) {
        this.skills = skills;
    }

    public List<String> getIndustries() {
        return industries;
    }

    public void setIndustries(List<String> industries) {
        this.industries = industries;
    }

    public String getExpectedSalary() {
        return expectedSalary;
    }

    public void setExpectedSalary(String expectedSalary) {
        this.expectedSalary = expectedSalary;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public List<String> getExperiences() {
        return experiences;
    }

    public void setExperiences(List<String> experiences) {
        this.experiences = experiences;
    }
}
