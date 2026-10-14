package com.shijie.resumehelper.job.api.dto;

import com.shijie.resumehelper.common.profile.ProfileDto;

/**
 * 岗位相关接口的请求体集合。
 *
 * <p>放在一个文件里是因为它们都很短，且总是成组出现；
 * 每个类都对应一个接口，见 {@code JobController} / {@code JobAgentController}。
 */
public final class JobRequests {

    private JobRequests() {
    }

    /**
     * {@code GET /api/jobs} 的查询参数（用对象接收，方便前端一次传全）。
     */
    public static class Search {
        /** 关键词，例如「Java」「后端开发」。留空表示不按关键词过滤。 */
        private String keyword;
        private String city;
        private String jobType;
        private Integer maxYears;
        private Integer salaryMin;
        private Boolean campusOnly;
        /** 城市是否作为硬筛选。默认 false。 */
        private Boolean strictCity;
        private Integer limit;
        private Integer explainTopN;
        /** 用户画像。不传也能搜，只是没有匹配度排序和差距分析。 */
        private ProfileDto profile;

        public String getKeyword() {
            return keyword;
        }

        public void setKeyword(String keyword) {
            this.keyword = keyword;
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

        public Integer getLimit() {
            return limit;
        }

        public void setLimit(Integer limit) {
            this.limit = limit;
        }

        public Integer getExplainTopN() {
            return explainTopN;
        }

        public void setExplainTopN(Integer explainTopN) {
            this.explainTopN = explainTopN;
        }

        public ProfileDto getProfile() {
            return profile;
        }

        public void setProfile(ProfileDto profile) {
            this.profile = profile;
        }
    }

    /**
     * {@code POST /api/jobs/import}：粘贴岗位描述。
     */
    public static class Import {
        /** 岗位描述原文（招聘网站复制过来的整段文本）。 */
        private String text;
        /** 原始链接，可为空。保留它是为了满足「岗位要能回溯到来源」的要求。 */
        private String sourceUrl;

        public String getText() {
            return text;
        }

        public void setText(String text) {
            this.text = text;
        }

        public String getSourceUrl() {
            return sourceUrl;
        }

        public void setSourceUrl(String sourceUrl) {
            this.sourceUrl = sourceUrl;
        }
    }

    /**
     * {@code POST /api/jobs/{id}/match}：计算某个岗位的匹配度与差距报告。
     */
    public static class Match {
        private ProfileDto profile;
        /** 是否调用大模型生成解释。默认 true；没有配置 token 时会自动退回规则解释。 */
        private Boolean explainAi;

        public ProfileDto getProfile() {
            return profile;
        }

        public void setProfile(ProfileDto profile) {
            this.profile = profile;
        }

        public Boolean getExplainAi() {
            return explainAi;
        }

        public void setExplainAi(Boolean explainAi) {
            this.explainAi = explainAi;
        }
    }

    /**
     * {@code POST /api/jobs/agent/run}：让智能体上网找岗位。
     */
    public static class AgentRun {
        private ProfileDto profile;
        private AgentOptionsDto options;

        public ProfileDto getProfile() {
            return profile;
        }

        public void setProfile(ProfileDto profile) {
            this.profile = profile;
        }

        public AgentOptionsDto getOptions() {
            return options;
        }

        public void setOptions(AgentOptionsDto options) {
            this.options = options;
        }
    }
}
