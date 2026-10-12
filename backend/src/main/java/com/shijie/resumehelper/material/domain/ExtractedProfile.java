package com.shijie.resumehelper.material.domain;

import com.shijie.resumehelper.common.util.TextKit;

import java.util.List;

/**
 * 从材料里抽取出来的结构化资料。对应产品方案 3.A：
 * 「解析文件中的文本，抽取教育、技能、项目、实习和工作经历」。
 *
 * <h2>为什么每条记录都带 evidence（原文片段）</h2>
 * 这是这个模块**最重要的设计决定**。原因有两个：
 *
 * <ol>
 *   <li><b>用户可以核对</b>。抽取结果页会把 evidence 显示在每一条旁边，
 *       用户一眼就能看出「这条是真的从我的简历里来的」还是 AI 编的。
 *       没有这个字段，用户只能盲信，而 AI 有机会一本正经地胡说。</li>
 *   <li><b>幻觉藏不住</b>。我们在提示词里要求模型必须给出原文片段，
 *       模型就很难凭空造一条经历——因为它得同时编出一段"原文"。
 *       后置校验还能检查这段 evidence 是否真的出现在原文里，见
 *       {@code ProfileExtractor} 的校验环节。</li>
 * </ol>
 *
 * <p>简历是要拿去投递的，编造经历等于害用户。所以宁可少抽几条，
 * 也不能多抽一条假的。
 *
 * @param summary           一句话自我描述（只基于原文事实）
 * @param highestEducation  最高学历：大专 / 本科 / 硕士 / 博士；判断不出来时为空串
 * @param yearsOfExperience 推算的相关经验年限；应届为 0
 * @param educations        教育经历
 * @param experiences       实习 / 工作经历
 * @param projects          项目经历
 * @param skills            技能关键词
 * @param highlights        获奖、证书、竞赛等
 * @param warnings          抽取过程中的提示，例如「原文较短」「某些字段没找到」
 */
public record ExtractedProfile(
        String summary,
        String highestEducation,
        Integer yearsOfExperience,
        List<Education> educations,
        List<Experience> experiences,
        List<Project> projects,
        List<String> skills,
        List<String> highlights,
        List<String> warnings) {

    /** 教育经历。 */
    public record Education(String school, String major, String degree, String period, String evidence) {
        public Education {
            school = TextKit.orEmpty(school);
            major = TextKit.orEmpty(major);
            degree = TextKit.orEmpty(degree);
            period = TextKit.orEmpty(period);
            evidence = TextKit.orEmpty(evidence);
        }

        public boolean isEmpty() {
            return school.isEmpty() && major.isEmpty() && degree.isEmpty();
        }
    }

    /** 实习或工作经历。 */
    public record Experience(String company, String role, String period, String description, String evidence) {
        public Experience {
            company = TextKit.orEmpty(company);
            role = TextKit.orEmpty(role);
            period = TextKit.orEmpty(period);
            description = TextKit.orEmpty(description);
            evidence = TextKit.orEmpty(evidence);
        }

        public boolean isEmpty() {
            return company.isEmpty() && role.isEmpty() && description.isEmpty();
        }
    }

    /** 项目经历。 */
    public record Project(String name, String role, String period, String description, String evidence) {
        public Project {
            name = TextKit.orEmpty(name);
            role = TextKit.orEmpty(role);
            period = TextKit.orEmpty(period);
            description = TextKit.orEmpty(description);
            evidence = TextKit.orEmpty(evidence);
        }

        public boolean isEmpty() {
            return name.isEmpty() && description.isEmpty();
        }
    }

    public ExtractedProfile {
        summary = TextKit.orEmpty(summary);
        highestEducation = TextKit.orEmpty(highestEducation);
        yearsOfExperience = yearsOfExperience == null ? 0 : Math.max(0, yearsOfExperience);
        educations = educations == null ? List.of() : List.copyOf(educations);
        experiences = experiences == null ? List.of() : List.copyOf(experiences);
        projects = projects == null ? List.of() : List.copyOf(projects);
        skills = skills == null ? List.of() : List.copyOf(skills);
        highlights = highlights == null ? List.of() : List.copyOf(highlights);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    /** 什么都没抽出来。用来判断要不要提示用户改用粘贴文本或手动填写。 */
    public boolean isEmpty() {
        return educations.isEmpty() && experiences.isEmpty() && projects.isEmpty() && skills.isEmpty();
    }

    /** 抽出的条目总数，用于在界面上显示「共识别 N 条经历」。 */
    public int itemCount() {
        return educations.size() + experiences.size() + projects.size() + skills.size() + highlights.size();
    }

    /** 一条都没抽出来时的空结果，附带原因。 */
    public static ExtractedProfile emptyWithWarning(String warning) {
        return new ExtractedProfile("", "", 0, List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(warning));
    }
}
