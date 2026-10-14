package com.shijie.resumehelper.resume.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.SafePaths;
import com.shijie.resumehelper.resume.config.ResumeModuleProperties;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 简历版本的文件系统实现。
 *
 * <pre>
 * {storageDir}/{userId}/versions/{versionId}.json
 * </pre>
 *
 * <p>一版一个文件：简历版本是"整体替换"的，不需要按字段更新，
 * 一个 JSON 文件正好，而且在编辑器里能直接看懂（排查"用户说保存后内容变了"很方便）。
 *
 * <p>路径都过 {@link SafePaths}：{@code userId} 与 {@code versionId} 来自请求，
 * 不清洗就能用 {@code ../} 写到目录外面。
 */
public class FileSystemResumeVersionStore implements ResumeVersionStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemResumeVersionStore.class);

    private static final String VERSIONS_DIR = "versions";
    private static final String DEFAULT_USER = "demo-user";

    private final Path root;
    private final ObjectMapper mapper;

    public FileSystemResumeVersionStore(ResumeModuleProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建简历存储目录：" + root, e);
        }
        log.info("简历存储目录：{}", root);
    }

    @Override
    public ResumeVersion save(ResumeVersion version) {
        Path file = versionFile(version.userId(), version.id());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(version),
                    StandardCharsets.UTF_8);
            return version;
        } catch (IOException e) {
            throw new UncheckedIOException("保存简历版本失败：" + version.id(), e);
        }
    }

    @Override
    public Optional<ResumeVersion> find(String userId, String versionId) {
        Path file = versionFile(userId, versionId);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        return read(file);
    }

    @Override
    public List<ResumeVersion> list(String userId) {
        Path dir = versionsDir(userId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<ResumeVersion> versions = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .forEach(path -> read(path).ifPresent(versions::add));
        } catch (IOException e) {
            throw new UncheckedIOException("列出简历版本失败", e);
        }
        versions.sort(Comparator.comparing(ResumeVersion::createdAt).reversed());
        return versions;
    }

    @Override
    public int count(String userId) {
        return list(userId).size();
    }

    @Override
    public boolean delete(String userId, String versionId) {
        Path file = versionFile(userId, versionId);
        try {
            boolean deleted = Files.deleteIfExists(file);
            if (deleted) {
                log.info("已删除简历版本 {}（用户 {}）", versionId, userId);
            }
            return deleted;
        } catch (IOException e) {
            throw new UncheckedIOException("删除简历版本失败：" + versionId, e);
        }
    }

    // ------------------------------------------------------------------ 内部

    private Path versionsDir(String userId) {
        return SafePaths.resolveInside(root, SafePaths.safeSegment(userId, DEFAULT_USER), VERSIONS_DIR);
    }

    private Path versionFile(String userId, String versionId) {
        return SafePaths.resolveInside(root,
                SafePaths.safeSegment(userId, DEFAULT_USER),
                VERSIONS_DIR,
                SafePaths.safeSegment(versionId, "unknown") + ".json");
    }

    private Optional<ResumeVersion> read(Path file) {
        try {
            return Optional.of(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), ResumeVersion.class));
        } catch (IOException e) {
            // 单个版本文件损坏不应该让整个列表接口挂掉
            log.warn("读取简历版本 {} 失败，已跳过：{}", file, e.getMessage());
            return Optional.empty();
        }
    }
}
