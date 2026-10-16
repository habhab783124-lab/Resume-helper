package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/** Offline identity catalog extracted from the official study plan; no problem statement corpus. */
public final class Hot100Catalog {
    public record Algorithm(String id, String title, String url, String difficulty) {}
    public record Problem(String id, String title, String slug, String difficulty) {
        public Algorithm overview() { return new Algorithm(id, title, "https://leetcode.cn/problems/" + slug + "/", difficulty); }
    }
    private static final List<Problem> PROBLEMS = load();
    private Hot100Catalog() {}
    public static List<Problem> problems() { return PROBLEMS; }
    public static Problem select() { return PROBLEMS.get(ThreadLocalRandom.current().nextInt(PROBLEMS.size())); }
    private static List<Problem> load() {
        try (var input = Hot100Catalog.class.getResourceAsStream("/interview/hot100.json")) {
            if (input == null) throw new IllegalStateException("缺少 Hot 100 目录");
            var root = new ObjectMapper().readTree(input);
            List<Problem> result = new ArrayList<>();
            Set<String> ids = new HashSet<>(), slugs = new HashSet<>();
            for (var item : root.path("problems")) {
                var problem = new Problem(item.path("id").asText(), item.path("title").asText(),
                        item.path("slug").asText(), item.path("difficulty").asText());
                if (!problem.id().matches("[0-9]+") || problem.title().isBlank() || !problem.slug().matches("[a-z0-9-]+")
                        || !Set.of("EASY", "MEDIUM", "HARD").contains(problem.difficulty())
                        || !ids.add(problem.id()) || !slugs.add(problem.slug())) throw new IllegalStateException("Hot 100 目录无效");
                result.add(problem);
            }
            if (result.size() != 100) throw new IllegalStateException("Hot 100 目录须包含100题");
            return List.copyOf(result);
        } catch (IOException e) { throw new IllegalStateException("无法读取 Hot 100 目录", e); }
    }
}
