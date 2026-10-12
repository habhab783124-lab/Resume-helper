package com.shijie.resumehelper.job.source;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.util.TextKit;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * CSV 岗位库来源。
 *
 * <p>对应产品方案 3.C 的兜底策略：「如果源站页面不稳定或使用规则不允许自动采集，
 * 就用人工整理的 CSV 演示岗位库，并给岗位保留原始来源链接」。
 * 学校/团队可以人工整理一份岗位表放进 CSV，用 Excel 维护即可。
 *
 * <p>表头（第一行，顺序可调）：
 * <pre>
 * title,company,city,district,salary,education,experience,jobType,skills,description,sourceUrl
 * </pre>
 * 其中 {@code skills} 用 {@code |} 分隔。
 */
public final class CsvJobSource implements JobSource {

    private static final String[] REQUIRED_HEADERS = {"title", "company"};

    private final String displayName;
    private final String location;
    private final boolean enabled;
    private volatile List<Job> cache;

    /**
     * @param displayName 展示用的来源名称
     * @param location    {@code classpath:jobs/demo-jobs.csv} 或绝对/相对文件路径
     * @param enabled     是否启用
     */
    public CsvJobSource(String displayName, String location, boolean enabled) {
        this.displayName = TextKit.isBlank(displayName) ? "CSV 岗位库" : displayName;
        this.location = TextKit.trim(location);
        this.enabled = enabled && !this.location.isEmpty();
    }

    @Override
    public String name() {
        return displayName;
    }

    @Override
    public String kind() {
        return "csv";
    }

    @Override
    public boolean enabled() {
        return enabled;
    }

    @Override
    public String description() {
        return enabled ? "人工整理的 CSV 岗位表" : "未配置 CSV 路径";
    }

    @Override
    public List<Job> search(JobQuery query) {
        return load().stream().filter(query::accepts).toList();
    }

    /** 惰性加载并缓存。CSV 文件不存在时视为空库，不抛异常，避免拖垮整次检索。 */
    public List<Job> load() {
        List<Job> cached = cache;
        if (cached != null) {
            return cached;
        }
        synchronized (this) {
            if (cache == null) {
                cache = parse(readLines());
            }
            return cache;
        }
    }

    /** 清空缓存，便于运维改完 CSV 后热加载。 */
    public void reload() {
        synchronized (this) {
            cache = null;
        }
    }

    private List<String> readLines() {
        try {
            if (location.startsWith("classpath:")) {
                String path = location.substring("classpath:".length());
                InputStream in = Thread.currentThread().getContextClassLoader().getResourceAsStream(path);
                if (in == null) {
                    return List.of();
                }
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    return reader.lines().toList();
                }
            }
            Path file = Path.of(location);
            if (!Files.isRegularFile(file)) {
                return List.of();
            }
            return Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new JobSourceException(name(), "读取 CSV 失败：" + e.getMessage(), e);
        }
    }

    private List<Job> parse(List<String> lines) {
        List<Job> jobs = new ArrayList<>();
        if (lines.isEmpty()) {
            return jobs;
        }
        List<String> header = TextKit.splitCsvLine(lines.get(0));
        for (String required : REQUIRED_HEADERS) {
            if (!header.contains(required)) {
                throw new JobSourceException(name(), "CSV 表头缺少必需列：" + required);
            }
        }
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line == null || line.isBlank()) {
                continue;
            }
            List<String> cells = TextKit.splitCsvLine(line);
            Job job = toJob(header, cells);
            if (job != null) {
                jobs.add(job);
            }
        }
        return jobs;
    }

    private Job toJob(List<String> header, List<String> cells) {
        String title = cell(header, cells, "title");
        if (TextKit.isBlank(title)) {
            return null;
        }
        String skillsRaw = cell(header, cells, "skills");
        List<String> skills = new ArrayList<>();
        if (!TextKit.isBlank(skillsRaw)) {
            for (String skill : skillsRaw.split("[|；;]")) {
                if (!skill.isBlank()) {
                    skills.add(skill.trim());
                }
            }
        }
        return Job.builder()
                .title(title)
                .company(cell(header, cells, "company"))
                .city(cell(header, cells, "city"))
                .district(cell(header, cells, "district"))
                .salary(cell(header, cells, "salary"))
                .education(cell(header, cells, "education"))
                .experience(cell(header, cells, "experience"))
                .jobType(cell(header, cells, "jobType"))
                .skills(skills)
                .description(cell(header, cells, "description"))
                .source(name(), cell(header, cells, "sourceUrl"))
                .collectedAt(Instant.now())
                .build();
    }

    private static String cell(List<String> header, List<String> cells, String column) {
        int index = header.indexOf(column);
        if (index < 0 || index >= cells.size()) {
            return "";
        }
        return cells.get(index);
    }
}
