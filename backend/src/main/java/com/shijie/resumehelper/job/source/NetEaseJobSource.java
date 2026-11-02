package com.shijie.resumehelper.job.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.match.SkillLexicon;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Public official listings, including full-time positions and internships; no model-generated jobs. */
public final class NetEaseJobSource implements JobSource {
    private static final URI ENDPOINT = URI.create("https://hr.163.com/api/hr163/position/queryPage");
    private final PublicJobJsonClient http;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();

    public NetEaseJobSource() { this(new PublicJobJsonClient()); }
    public NetEaseJobSource(PublicJobJsonClient http) { this.http = http; }
    @Override public String name() { return "网易招聘官网"; }
    @Override public String kind() { return "public-api"; }
    @Override public String description() { return "联网检索网易官方全职和实习岗位，保留原始招聘链接；未公开薪资显示面议。"; }

    @Override public List<Job> search(JobQuery query) {
        String keyword = TextKit.truncate(query.keyword(), 100);
        Map<String, Job> jobs = new LinkedHashMap<>();
        collectPages(keyword, query, jobs);
        if (keyword.contains("网易") && jobs.values().stream().noneMatch(job -> query.accepts(job) && query.matchesKeyword(job, keyword))) {
            collectPages("", query, jobs);
        }
        return List.copyOf(jobs.values());
    }

    private void collectPages(String keyword, JobQuery query, Map<String, Job> jobs) {
        for (int page = 1; page <= 10; page++) {
            Cached result = page(keyword, page);
            result.jobs().forEach(job -> jobs.putIfAbsent(job.id(), job));
            long accepted = jobs.values().stream().filter(query::accepts)
                    .filter(job -> query.matchesKeyword(job, query.keyword())).count();
            if (accepted >= query.limit() || page * 100 >= result.total() || result.jobs().isEmpty()) break;
        }
    }

    private Cached page(String keyword, int page) {
        String key = keyword + "#" + page;
        Cached saved = cache.get(key);
        if (saved != null && saved.at().plusSeconds(300).isAfter(Instant.now())) return saved;
        try {
            JsonNode root = http.post(ENDPOINT, Map.of("currentPage", page, "pageSize", 100, "keyword", keyword));
            JsonNode data = root.path("data");
            if (root.path("code").asInt() != 200 || !data.path("list").isArray())
                throw new IllegalStateException("官方招聘接口暂不可用");
            List<Job> jobs = parse(data.path("list")).stream()
                    .map(job -> keyword.isBlank() ? job : job.toBuilder().searchKeywords(List.of(keyword)).build()).toList();
            if (cache.size() > 100) cache.clear();
            Cached result = new Cached(Instant.now(), jobs, data.path("total").asInt(jobs.size()));
            cache.put(key, result);
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new JobSourceException(name(), "联网检索已中断");
        } catch (Exception e) {
            throw new JobSourceException(name(), "无法连接网易招聘官网，请检查网络或稍后重试");
        }
    }

    public List<Job> parse(JsonNode posts) {
        List<Job> jobs = new ArrayList<>();
        for (JsonNode post : posts) {
            String id = post.path("id").asText("");
            String title = post.path("name").asText("");
            if (!id.matches("[0-9]{1,30}") || title.isBlank()) continue;
            List<String> cities = new ArrayList<>();
            if (post.path("workPlaceNameList").isArray()) post.path("workPlaceNameList").forEach(city -> {
                if (city.isTextual() && !city.asText().isBlank()) cities.add(city.asText().replaceFirst("市$", ""));
            });
            String experience = post.path("reqWorkYearsName").asText("经验未披露");
            String description = TextKit.joinNonBlank("\n\n", post.path("description").asText(""),
                    post.path("requirement").asText("").isBlank() ? "" : "任职要求：\n" + post.path("requirement").asText());
            boolean internship = post.path("workType").asText("").equals("1") || title.contains("实习");
            jobs.add(Job.builder().id("netease-" + id).title(title).company("网易")
                    .city(String.join(" / ", cities)).salary("面议")
                    .education(post.path("reqEducationName").asText("未披露"))
                    .experience(experience).minYears(TextKit.parseMinYears(experience))
                    .campus(internship || TextKit.isCampusFriendly(experience, title)).jobType(internship ? "实习" : "全职")
                    .skills(new ArrayList<>(SkillLexicon.extract(title + "\n" + description)))
                    .description(TextKit.joinNonBlank("\n\n", post.path("productName").asText(""), description))
                    .source(name(), "https://hr.163.com/job-detail.html?id=" + id).build());
        }
        return List.copyOf(jobs);
    }

    private record Cached(Instant at, List<Job> jobs, int total) {}
}
