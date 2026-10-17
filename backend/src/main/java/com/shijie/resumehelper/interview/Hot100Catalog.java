package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.IntUnaryOperator;

/** Offline identity catalog extracted from the official study plan; no problem statement corpus. */
public final class Hot100Catalog {
    public record Algorithm(String id, String title, String url, String difficulty) {}
    public record Problem(String id, String title, String slug, String difficulty) {
        public Algorithm overview() { return new Algorithm(id, title, "https://leetcode.cn/problems/" + slug + "/", difficulty); }
    }
    private static final List<Problem> PROBLEMS = load();
    private final IntUnaryOperator randomIndex;
    public Hot100Catalog() { this(bound -> ThreadLocalRandom.current().nextInt(bound)); }
    Hot100Catalog(IntUnaryOperator randomIndex) { this.randomIndex = Objects.requireNonNull(randomIndex); }
    public static List<Problem> problems() { return PROBLEMS; }
    /** Compatibility selector for legacy-v3 sessions, which have no confirmed difficulty. */
    public static Problem select() { return PROBLEMS.get(ThreadLocalRandom.current().nextInt(PROBLEMS.size())); }
    public Problem select(String difficulty, Set<String> excludedIds) {
        return select(difficulty, excludedIds, false);
    }
    public Problem select(String difficulty, Set<String> excludedIds, boolean allowRepeat) {
        if (difficulty == null || !Set.of("EASY", "MEDIUM", "HARD").contains(difficulty))
            throw new IllegalArgumentException("算法难度必须为 EASY、MEDIUM 或 HARD");
        Set<String> excluded = excludedIds == null ? Set.of() : excludedIds;
        List<Problem> candidates = PROBLEMS.stream()
                .filter(problem -> difficulty.equals(problem.difficulty()))
                .filter(problem -> !excluded.contains(problem.id()))
                .toList();
        if (candidates.isEmpty() && allowRepeat)
            candidates = PROBLEMS.stream().filter(problem -> difficulty.equals(problem.difficulty())).toList();
        if (candidates.isEmpty()) throw new IllegalStateException("该难度的算法题已全部练习，请勾选确认后允许重新抽题");
        int index = randomIndex.applyAsInt(candidates.size());
        if (index < 0 || index >= candidates.size()) throw new IllegalStateException("算法题随机索引无效");
        return candidates.get(index);
    }
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
