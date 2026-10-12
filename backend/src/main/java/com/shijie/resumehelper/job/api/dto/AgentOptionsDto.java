package com.shijie.resumehelper.job.api.dto;

import com.shijie.resumehelper.job.agent.AgentOptions;

/**
 * 前端可控的智能体 / 检索参数。所有字段都可以不传，会落到服务端默认值。
 */
public class AgentOptionsDto {

    /** 智能体最多检索几轮。 */
    private Integer maxRounds;
    /** 返回条数。 */
    private Integer limit;
    /** 指定检索城市；不传则用画像里的期望城市。 */
    private String city;
    /** 工作类型：全职 / 校招 / 实习。 */
    private String jobType;
    /** 岗位最低年限上限，例如应届生传 1，用来挡掉「3 年以上」。 */
    private Integer maxYears;
    /** 薪资下限（千元/月），低于此值的岗位直接剔除。 */
    private Integer salaryMin;
    /** 只看校招 / 应届友好岗位。 */
    private Boolean campusOnly;
    /** 城市是否作为硬筛选条件。默认 false：城市参与打分，让用户看到更多岗位和差距。 */
    private Boolean strictCity;
    /** 前 N 个岗位调用大模型生成匹配解释；0 表示不调用。 */
    private Integer explainTopN;

    /**
     * 转成 core 层的参数对象。
     *
     * @param defaultMaxRounds  服务端默认轮数
     * @param defaultLimit      服务端默认条数
     * @param defaultExplainTopN 服务端默认解释条数
     * @param maxLimit          条数上限，防止前端传超大值
     */
    public AgentOptions toOptions(int defaultMaxRounds, int defaultLimit, int defaultExplainTopN, int maxLimit) {
        int effectiveLimit = limit == null ? defaultLimit : Math.min(Math.max(1, limit), maxLimit);
        return AgentOptions.builder()
                .maxRounds(maxRounds == null ? defaultMaxRounds : maxRounds)
                .limit(effectiveLimit)
                .city(city)
                .jobType(jobType)
                .maxMinYears(maxYears)
                .salaryMin(salaryMin)
                .campusOnly(Boolean.TRUE.equals(campusOnly))
                .strictCity(Boolean.TRUE.equals(strictCity))
                .explainTopN(explainTopN == null ? defaultExplainTopN : Math.min(explainTopN, effectiveLimit))
                .build();
    }

    public Integer getMaxRounds() {
        return maxRounds;
    }

    public void setMaxRounds(Integer maxRounds) {
        this.maxRounds = maxRounds;
    }

    public Integer getLimit() {
        return limit;
    }

    public void setLimit(Integer limit) {
        this.limit = limit;
    }

    public String getCity() {
        return city;
    }

    public void setCity(String city) {
        this.city = city;
    }

    public String getJobType() {
        return jobType;
    }

    public void setJobType(String jobType) {
        this.jobType = jobType;
    }

    public Integer getMaxYears() {
        return maxYears;
    }

    public void setMaxYears(Integer maxYears) {
        this.maxYears = maxYears;
    }

    public Integer getSalaryMin() {
        return salaryMin;
    }

    public void setSalaryMin(Integer salaryMin) {
        this.salaryMin = salaryMin;
    }

    public Boolean getCampusOnly() {
        return campusOnly;
    }

    public void setCampusOnly(Boolean campusOnly) {
        this.campusOnly = campusOnly;
    }

    public Boolean getStrictCity() {
        return strictCity;
    }

    public void setStrictCity(Boolean strictCity) {
        this.strictCity = strictCity;
    }

    public Integer getExplainTopN() {
        return explainTopN;
    }

    public void setExplainTopN(Integer explainTopN) {
        this.explainTopN = explainTopN;
    }
}
