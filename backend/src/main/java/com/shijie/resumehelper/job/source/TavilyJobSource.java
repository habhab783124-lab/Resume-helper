package com.shijie.resumehelper.job.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.match.SkillLexicon;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;

/** Optional wider web search. Only public job-page snippets and URLs are used as evidence. */
public final class TavilyJobSource implements JobSource {
    private final String apiKey;
    private final PublicJobJsonClient http;
    private final ConcurrentHashMap<String, Cached> cache = new ConcurrentHashMap<>();
    public TavilyJobSource(String apiKey) { this(apiKey, new PublicJobJsonClient()); }
    public TavilyJobSource(String apiKey, PublicJobJsonClient http) { this.apiKey = TextKit.trim(apiKey); this.http = http; }
    @Override public String name() { return "公开招聘网页检索"; }
    @Override public String kind() { return "search"; }
    @Override public boolean enabled() { return !apiKey.isBlank(); }
    @Override public String description() { return enabled() ? "搜索公开招聘页面；摘要信息须到原始链接核实。" : "设置服务端 TAVILY_API_KEY 后可跨招聘网站联网检索。"; }

    @Override public List<Job> search(JobQuery query) {
        if (!enabled()) return List.of();
        String terms = TextKit.truncate(query.keyword() + " " + query.city() + " 招聘 职位 岗位职责 任职要求", 250);
        Cached previous = cache.get(terms);
        if (previous != null && previous.at().plusSeconds(300).isAfter(Instant.now())) return previous.jobs();
        try {
            JsonNode root = http.post(URI.create("https://api.tavily.com/search"), apiKey,
                    Map.of("query", terms, "search_depth", "basic", "max_results", 10,
                            "include_answer", false, "include_raw_content", false));
            if (!root.path("results").isArray()) throw new IllegalStateException("搜索服务没有返回岗位结果");
            List<Job> jobs = parse(root.path("results"), query.city());
            if (cache.size() > 100) cache.clear();
            cache.put(terms, new Cached(Instant.now(), jobs));
            return jobs;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw new JobSourceException(name(), "网页搜索已中断");
        } catch (Exception e) {
            throw new JobSourceException(name(), "联网搜索暂时失败，请检查搜索服务的密钥、额度与网络");
        }
    }

    public List<Job> parse(JsonNode results, String preferredCity) {
        List<Job> jobs = new ArrayList<>();
        for (JsonNode result : results) {
            String url = result.path("url").asText("");
            String title = result.path("title").asText("");
            String content = result.path("content").asText("");
            URI uri;
            try { uri = URI.create(url); } catch (RuntimeException ignored) { continue; }
            if (!List.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || title.isBlank() || content.isBlank()) continue;
            String searchable = title + "\n" + content;
            // Search engines also return advice articles and index pages; do not present these as job offers.
            if (!searchable.matches("(?s).*(岗位职责|任职要求|职位描述|招聘职位|job description|Responsibilities|Qualifications).*")
                    || searchable.matches("(?s).*(求职攻略|面试攻略|招聘骗局|求职指南).*") || content.length() < 60) continue;
            // Keep fields absent from the snippet explicitly unknown; never infer a company or a salary.
            Job parsed = new JobImporter().parse("岗位名称：" + title + "\n" + content, url);
            String city = parsed.city();
            if (city.isBlank() && !preferredCity.isBlank() && searchable.contains(preferredCity)) city = preferredCity;
            jobs.add(parsed.toBuilder().id(TextKit.stableId("web-", url)).city(city)
                    .company("手动导入".equals(parsed.company()) ? "公司未披露" : parsed.company())
                    .education("不限".equals(parsed.education()) && !content.contains("学历不限") ? "未披露" : parsed.education())
                    .experience("经验不限".equals(parsed.experience()) && !content.contains("经验不限") ? "经验未披露" : parsed.experience())
                    .skills(new ArrayList<>(SkillLexicon.extract(searchable)))
                    .description(content + "\n\n以上为搜索引擎摘要，招聘状态和完整要求请以原始页面为准。")
                    .source(name() + " · " + uri.getHost(), url).build());
        }
        return List.copyOf(jobs);
    }

    private record Cached(Instant at, List<Job> jobs) {}
}
