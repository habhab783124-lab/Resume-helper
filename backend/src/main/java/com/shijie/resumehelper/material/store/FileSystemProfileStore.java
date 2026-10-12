package com.shijie.resumehelper.material.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.SafePaths;
import com.shijie.resumehelper.material.config.MaterialModuleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * 画像的文件系统实现：{@code {storageDir}/{userId}/profile.json}。
 *
 * <p>存成人类可读的 JSON 而不是二进制：排查"岗位匹配为什么推荐了这个"时，
 * 直接打开文件就能看到当时用的画像是什么，比查库快得多。
 */
public class FileSystemProfileStore implements ProfileStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemProfileStore.class);

    private static final String PROFILE_FILE = "profile.json";
    private static final String DEFAULT_USER = "demo-user";

    private final Path root;
    private final ObjectMapper mapper;

    public FileSystemProfileStore(MaterialModuleProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
    }

    @Override
    public void save(String userId, UserProfileSnapshot profile) {
        Path file = profileFile(userId);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("保存求职画像失败", e);
        }
    }

    @Override
    public Optional<UserProfileSnapshot> find(String userId) {
        Path file = profileFile(userId);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8),
                    UserProfileSnapshot.class));
        } catch (IOException e) {
            // 画像文件坏了不应该让整个页面打不开，当作"还没填过"处理
            log.warn("读取求职画像失败（{}）：{}", file, e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public boolean delete(String userId) {
        try {
            return Files.deleteIfExists(profileFile(userId));
        } catch (IOException e) {
            throw new UncheckedIOException("删除求职画像失败", e);
        }
    }

    private Path profileFile(String userId) {
        return SafePaths.resolveInside(root, SafePaths.safeSegment(userId, DEFAULT_USER), PROFILE_FILE);
    }
}
