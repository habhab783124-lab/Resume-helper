package com.shijie.resumehelper.job.agent;

/**
 * 智能体运行参数。
 *
 * @param maxRounds     最多检索轮数。轮数越多越费 token，3 轮足够覆盖「换关键词扩检」的场景
 * @param limit         最终返回的岗位数量
 * @param city          指定城市；为空时用用户画像里的期望城市
 * @param jobType       工作类型筛选：全职 / 校招 / 实习
 * @param maxMinYears   岗位最低年限上限，用来过滤「要求 3 年以上」的岗位
 * @param salaryMin     期望薪资下限（千元/月），低于此值的岗位直接剔除；null 表示不限
 * @param campusOnly    只看校招/应届友好岗位
 * @param strictCity    城市是否作为硬筛选条件
 * @param explainTopN   前 N 个岗位调用大模型生成匹配解释；0 表示不调用
 */
public record AgentOptions(
        int maxRounds,
        int limit,
        String city,
        String jobType,
        Integer maxMinYears,
        Integer salaryMin,
        boolean campusOnly,
        boolean strictCity,
        int explainTopN) {

    public static AgentOptions defaults() {
        return new AgentOptions(3, 12, "", "", null, null, false, false, 5);
    }

    public AgentOptions {
        maxRounds = maxRounds <= 0 ? 1 : Math.min(maxRounds, 6);
        limit = limit <= 0 ? 12 : Math.min(limit, 50);
        city = city == null ? "" : city.trim();
        jobType = jobType == null ? "" : jobType.trim();
        explainTopN = Math.max(0, Math.min(explainTopN, limit));
    }

    public Builder toBuilder() {
        return new Builder(this);
    }

    public static Builder builder() {
        return new Builder(defaults());
    }

    public static final class Builder {
        private int maxRounds;
        private int limit;
        private String city;
        private String jobType;
        private Integer maxMinYears;
        private Integer salaryMin;
        private boolean campusOnly;
        private boolean strictCity;
        private int explainTopN;

        private Builder(AgentOptions base) {
            this.maxRounds = base.maxRounds;
            this.limit = base.limit;
            this.city = base.city;
            this.jobType = base.jobType;
            this.maxMinYears = base.maxMinYears;
            this.salaryMin = base.salaryMin;
            this.campusOnly = base.campusOnly;
            this.strictCity = base.strictCity;
            this.explainTopN = base.explainTopN;
        }

        public Builder maxRounds(int v) {
            this.maxRounds = v;
            return this;
        }

        public Builder limit(int v) {
            this.limit = v;
            return this;
        }

        public Builder city(String v) {
            this.city = v;
            return this;
        }

        public Builder jobType(String v) {
            this.jobType = v;
            return this;
        }

        public Builder maxMinYears(Integer v) {
            this.maxMinYears = v;
            return this;
        }

        public Builder salaryMin(Integer v) {
            this.salaryMin = v;
            return this;
        }

        public Builder campusOnly(boolean v) {
            this.campusOnly = v;
            return this;
        }

        public Builder strictCity(boolean v) {
            this.strictCity = v;
            return this;
        }

        public Builder explainTopN(int v) {
            this.explainTopN = v;
            return this;
        }

        public AgentOptions build() {
            return new AgentOptions(maxRounds, limit, city, jobType, maxMinYears, salaryMin, campusOnly, strictCity, explainTopN);
        }
    }
}
