package com.shijie.resumehelper.job.match;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobMatchResult;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.TextKit;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 规则匹配打分器（对应产品方案 3.C 的匹配逻辑）。
 *
 * <p>先用城市 / 工作类型 / 最低年限做硬筛选（在 {@code JobQuery#accepts} 里），
 * 再把技能、经历、学历、薪资折算成 0~100 的贴合度。<b>这个分数表示资料与岗位的贴合程度，
 * 不是录取概率</b>，前端展示时需要保留这句说明。
 *
 * <p>规则分先给出可解释的基线，AI 再在此基础上补充「为什么匹配 / 差在哪里」的自然语言解释。
 * 即使大模型不可用，岗位页也能正常打出分数和差距。
 */
public final class MatchScorer {

    /** 各维度权重，总和必须为 100。 */
    public record Weights(int skill, int title, int city, int experience, int education, int salary) {

        public static Weights defaults() {
            return new Weights(45, 20, 15, 10, 5, 5);
        }

        /**
         * 注意：紧凑构造器里必须用「参数」求和，不能用 {@link #total()}。
         * record 的字段是在紧凑构造器执行完之后才赋值的，
         * 此时调用实例方法读到的全是默认值 0。
         */
        public Weights {
            int sum = skill + title + city + experience + education + salary;
            if (sum != 100) {
                throw new IllegalArgumentException("匹配权重之和必须为 100，当前为 " + sum);
            }
        }

        public int total() {
            return skill + title + city + experience + education + salary;
        }
    }

    private final Weights weights;

    public MatchScorer() {
        this(Weights.defaults());
    }

    public MatchScorer(Weights weights) {
        this.weights = weights == null ? Weights.defaults() : weights;
    }

    public Weights weights() {
        return weights;
    }

    /** 用户的技能集合：显式填写的技能 + 从经历文本里识别出的技能。 */
    public static Set<String> userSkills(UserProfileSnapshot profile) {
        Set<String> skills = new LinkedHashSet<>(SkillLexicon.canonicalize(profile.skills()));
        skills.addAll(SkillLexicon.extract(profile.allText()));
        return skills;
    }

    /** 对单个岗位打分。 */
    public JobMatchResult score(Job job, UserProfileSnapshot profile) {
        Set<String> userSkills = userSkills(profile);
        Set<String> jobSkills = SkillLexicon.extractFrom(job);

        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String skill : jobSkills) {
            if (userSkills.contains(skill)) {
                matched.add(skill);
            } else {
                missing.add(skill);
            }
        }

        Map<String, Integer> dimensions = new LinkedHashMap<>();
        List<String> reasons = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        dimensions.put("技能匹配", scoreSkill(jobSkills.size(), matched.size(), reasons));
        dimensions.put("岗位方向", scoreTitle(job, profile, reasons));
        dimensions.put("城市", scoreCity(job, profile, reasons, gaps));
        dimensions.put("经验", scoreExperience(job, profile, reasons, gaps));
        dimensions.put("学历", scoreEducation(job, profile, reasons, gaps));
        dimensions.put("薪资", scoreSalary(job, profile, reasons, gaps));

        if (!missing.isEmpty()) {
            gaps.add("岗位还要求 " + String.join("、", firstN(missing, 5)) + "，建议补充对应的项目或课程经历");
        }
        if (job.campus() && profile.yearsOfExperience() <= 1) {
            reasons.add("该岗位面向应届生/校招，经验门槛与你匹配");
        }

        int score = dimensions.values().stream().mapToInt(Integer::intValue).sum();
        return new JobMatchResult(job, score, matched, missing, reasons, gaps, dimensions, "");
    }

    /** 批量打分并按贴合度降序排序。 */
    public List<JobMatchResult> rank(Collection<Job> jobs, UserProfileSnapshot profile, int limit) {
        List<JobMatchResult> results = new ArrayList<>();
        if (jobs != null) {
            for (Job job : jobs) {
                results.add(score(job, profile));
            }
        }
        results.sort(Comparator.comparingInt(JobMatchResult::score).reversed()
                .thenComparing(r -> r.job().id()));
        return limit > 0 && results.size() > limit ? new ArrayList<>(results.subList(0, limit)) : results;
    }

    private int scoreSkill(int jobSkillCount, int matchedCount, List<String> reasons) {
        if (jobSkillCount == 0) {
            // 岗位没标注技能，给中性分，避免因为来源信息缺失而误杀
            return Math.round(weights.skill() * 0.5f);
        }
        float ratio = Math.min(1.0f, (float) matchedCount / jobSkillCount);
        int value = Math.round(weights.skill() * ratio);
        if (matchedCount > 0) {
            reasons.add("技能命中 " + matchedCount + "/" + jobSkillCount + " 项");
        }
        return value;
    }

    private int scoreTitle(Job job, UserProfileSnapshot profile, List<String> reasons) {
        if (profile.targetTitle().isEmpty()) {
            return Math.round(weights.title() * 0.6f);
        }
        double similarity = TextKit.dice(profile.targetTitle(), job.title());
        int value = (int) Math.round(weights.title() * similarity);
        if (similarity >= 0.5) {
            reasons.add("岗位方向与你的目标职位「" + profile.targetTitle() + "」一致");
        }
        return value;
    }

    private int scoreCity(Job job, UserProfileSnapshot profile, List<String> reasons, List<String> gaps) {
        if (profile.targetCities().isEmpty()) {
            return Math.round(weights.city() * 0.8f);
        }
        if (job.city().isEmpty()) {
            return Math.round(weights.city() * 0.6f);
        }
        if (job.cityMatches(profile.targetCities())) {
            reasons.add("工作地点 " + job.place() + " 在你的期望城市内");
            return weights.city();
        }
        gaps.add("工作地点 " + job.place() + " 不在你期望的 " + String.join("、", profile.targetCities()) + " 范围内");
        return 0;
    }

    /**
     * 经验维度。
     *
     * <p>刻意不做「差一年也算友好」的宽松处理：应届生（0 年）投「1-3 年」的岗位是真实存在的差距，
     * 只是没有「3 年以上」那么硬，所以给 0.7 分并明确提示用项目经历弥补，
     * 而不是直接说成「经验要求比较友好」。
     */
    private int scoreExperience(Job job, UserProfileSnapshot profile, List<String> reasons, List<String> gaps) {
        int need = job.minYears();
        int have = profile.yearsOfExperience();
        String haveText = have <= 0 ? "应届/无全职经验" : have + " 年经验";

        float ratio;
        if (need <= have) {
            ratio = 1.0f;
        } else if (need == have + 1) {
            ratio = 0.7f;
        } else if (need <= have + 3) {
            ratio = 0.4f;
        } else {
            ratio = 0.15f;
        }

        if (ratio >= 1.0f) {
            reasons.add("经验要求（" + job.experience() + "）对当前的你比较友好");
        } else if (ratio <= 0.4f) {
            gaps.add("岗位要求 " + job.experience() + "，明显高于你目前的 " + haveText);
        } else {
            gaps.add("岗位要求 " + job.experience() + "，略高于你目前的 " + haveText + "，建议用项目经历弥补");
        }
        return Math.round(weights.experience() * ratio);
    }

    private int scoreEducation(Job job, UserProfileSnapshot profile, List<String> reasons, List<String> gaps) {
        int need = TextKit.educationRank(job.education());
        if (need == 0) {
            return weights.education();
        }
        if (profile.education().isEmpty()) {
            return Math.round(weights.education() * 0.7f);
        }
        int have = TextKit.educationRank(profile.education());
        if (have >= need) {
            return weights.education();
        }
        if (have == need - 1) {
            gaps.add("岗位要求 " + job.education() + "，你目前是 " + profile.education());
            return Math.round(weights.education() * 0.5f);
        }
        gaps.add("岗位学历要求 " + job.education() + "，高于你目前的 " + profile.education());
        return Math.round(weights.education() * 0.1f);
    }

    private int scoreSalary(Job job, UserProfileSnapshot profile, List<String> reasons, List<String> gaps) {
        int expected = profile.expectedSalaryMin();
        int offered = job.salaryMax();
        if (expected <= 0 || offered <= 0) {
            // 任一侧是「面议」，给中性分，不用薪资影响排序
            return Math.round(weights.salary() * 0.75f);
        }
        if (offered >= expected) {
            reasons.add("薪资区间 " + job.salary() + " 覆盖你的期望");
            return weights.salary();
        }
        if (offered >= expected * 0.8) {
            return Math.round(weights.salary() * 0.6f);
        }
        gaps.add("薪资上限 " + job.salary() + " 低于你的期望");
        return Math.round(weights.salary() * 0.25f);
    }

    private static List<String> firstN(List<String> list, int n) {
        return list.size() <= n ? list : new ArrayList<>(list.subList(0, n));
    }
}
