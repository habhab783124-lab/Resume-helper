package com.shijie.resumehelper.job.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.match.SkillLexicon;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Tencent's public, login-free recruitment API. Never generates listings with a language model. */
public final class TencentJobSource implements JobSource {
    private static final String ENDPOINT = "https://careers.tencent.com/tencentcareer/api/post/Query";
    private final PublicJobJsonClient http;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    public TencentJobSource() { this(new PublicJobJsonClient()); }
    public TencentJobSource(PublicJobJsonClient http) { this.http = http; }
    @Override public String name() { return "腾讯招聘官网"; }
    @Override public String kind() { return "public-api"; }
    @Override public String description() { return "联网检索腾讯官方公开岗位，保留原始招聘链接；薪资未公开时显示面议。"; }

    @Override public List<Job> search(JobQuery query) {
        // 公司名、多个技能组合由本地完整匹配；官网关键词检索不能覆盖这些情况。
        String keyword = TextKit.truncate(query.keyword(), 100);
        List<Job> jobs = new ArrayList<>();
        collectPages(keyword, query, jobs);
        if (!keyword.isBlank() && jobs.stream().noneMatch(job -> query.accepts(job) && query.matchesKeyword(job, keyword))) {
            collectPages("", query, jobs);
        }
        return List.copyOf(jobs);
    }

    private void collectPages(String keyword, JobQuery query, List<Job> jobs) {
        for (int page = 1; page <= 10; page++) {
            Cached result = page(keyword, page);
            jobs.addAll(result.jobs());
            long accepted = jobs.stream().filter(query::accepts)
                    .filter(job -> query.matchesKeyword(job, query.keyword())).count();
            if (accepted >= query.limit() || page * 100 >= result.total() || result.jobs().isEmpty()) break;
        }
    }

    private Cached page(String keyword, int page) {
        String key = keyword + "#" + page;
        Cached saved = cache.get(key);
        if (saved != null && saved.at().plusSeconds(300).isAfter(Instant.now())) return saved;
        try {
            URI uri = URI.create(ENDPOINT + "?keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8)
                    + "&pageIndex=" + page + "&pageSize=100&language=zh-cn&area=cn");
            JsonNode root = http.get(uri);
            if (root.path("Code").asInt() != 200 || !root.path("Data").path("Posts").isArray())
                throw new IllegalStateException("官方招聘接口暂不可用，请稍后重试");
            List<Job> jobs = parse(root.path("Data").path("Posts")).stream()
                    .map(job -> keyword.isBlank() ? job : job.toBuilder().searchKeywords(List.of(keyword)).build()).toList();
            if (cache.size() > 100) cache.clear();
            Cached result = new Cached(Instant.now(), jobs, root.path("Data").path("Count").asInt(jobs.size()));
            cache.put(key, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobSourceException(name(), "联网检索已中断");
        } catch (Exception e) {
            throw new JobSourceException(name(), "无法连接腾讯招聘官网，请检查网络或稍后重试");
        }
    }

    public List<Job> parse(JsonNode posts) {
        List<Job> jobs = new ArrayList<>();
        for (JsonNode post : posts) {
            String id = post.path("PostId").asText("");
            String title = post.path("RecruitPostName").asText("");
            if (!id.matches("[0-9]{1,30}") || title.isBlank() || !post.path("IsValid").asBoolean(true)) continue;
            String description = post.path("Responsibility").asText("");
            String experience = post.path("RequireWorkYearsName").asText("经验未披露");
            String company = post.path("ComName").asText("");
            jobs.add(Job.builder().id("tencent-" + id).title(title).company(company.isBlank() ? "腾讯" : company)
                    .city(post.path("LocationName").asText("")).salary("面议").education("未披露")
                    .experience(experience).minYears(minYears(experience)).campus(TextKit.isCampusFriendly(experience, title))
                    .jobType(title.contains("实习") ? "实习" : "全职")
                    .skills(new ArrayList<>(SkillLexicon.extract(title + "\n" + description)))
                    .description(description + "\n\n官方更新时间：" + post.path("LastUpdateTime").asText("未提供"))
                    .source(name(), "https://careers.tencent.com/jobdesc.html?postId=" + id).build());
        }
        return List.copyOf(jobs);
    }

    static int minYears(String value) {
        String text = value.replace("两", "二");
        String[] numbers = {"一", "二", "三", "四", "五", "六", "七", "八", "九", "十"};
        for (int i = numbers.length - 1; i >= 0; i--) text = text.replace(numbers[i], String.valueOf(i + 1));
        return TextKit.parseMinYears(text);
    }

    private record Cached(Instant at, List<Job> jobs, int total) {}
}
