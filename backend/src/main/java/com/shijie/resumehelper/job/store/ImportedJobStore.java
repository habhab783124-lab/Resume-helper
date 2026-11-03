package com.shijie.resumehelper.job.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.SafePaths;
import com.shijie.resumehelper.job.domain.Job;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** 用户粘贴的岗位独立保存，刷新、重启后仍可检索，且不进入他人的候选池。 */
@Component
public class ImportedJobStore {
    private final Path root;
    private final ObjectMapper mapper;

    public ImportedJobStore(@Value("${app.job.storage-dir:./data/jobs}") String directory, ObjectMapper mapper) {
        this.root = Path.of(directory).toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    public synchronized void save(String userId, Job job) {
        Path directory = directory(userId);
        try {
            Files.createDirectories(directory);
            Path temporary = Files.createTempFile(directory, "import-", ".tmp");
            try {
                mapper.writeValue(temporary.toFile(), job);
                Files.move(temporary, directory.resolve(job.id() + ".json"), StandardCopyOption.REPLACE_EXISTING);
            } finally { Files.deleteIfExists(temporary); }
        } catch (IOException e) { throw new UncheckedIOException("保存导入岗位失败，请重试", e); }
    }

    public synchronized List<Job> list(String userId) {
        Path directory = directory(userId);
        if (!Files.isDirectory(directory)) return List.of();
        try (var files = Files.list(directory)) {
            return files.filter(file -> file.getFileName().toString().endsWith(".json"))
                    .map(this::read).sorted(Comparator.comparing(Job::collectedAt).reversed()).toList();
        } catch (IOException e) { throw new UncheckedIOException("读取导入岗位失败，请重试", e); }
    }

    public Optional<Job> find(String userId, String id) {
        if (id == null || !id.matches("imported-[a-f0-9]{64}")) return Optional.empty();
        Path file = directory(userId).resolve(id + ".json");
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    private Job read(Path file) {
        try { return mapper.readValue(file.toFile(), Job.class); }
        catch (IOException e) { throw new UncheckedIOException("读取导入岗位失败，请重试", e); }
    }

    private Path directory(String userId) {
        return SafePaths.resolveInside(root, SafePaths.safeSegment(userId, "demo-user"));
    }
}
