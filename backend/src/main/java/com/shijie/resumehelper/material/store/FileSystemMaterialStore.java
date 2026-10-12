package com.shijie.resumehelper.material.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.SafePaths;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.material.config.MaterialModuleProperties;
import com.shijie.resumehelper.material.domain.ExtractedProfile;
import com.shijie.resumehelper.material.domain.Material;
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
 * 文件系统实现。目录结构刻意做成"人可以直接看懂"的样子：
 *
 * <pre>
 * {storageDir}/{userId}/
 *     profile.json
 *     materials/{materialId}/
 *         original.pdf      用户上传的原文件
 *         text.txt          抽出的原文（「原文对照」用）
 *         extraction.json   结构化抽取结果
 *         meta.json         材料元数据
 * </pre>
 *
 * <p>这样出问题时可以直接打开目录看「到底抽出了什么」「模型返回了什么」，
 * 排查抽取质量比查数据库舒服得多。缺点是不适合高并发和大规模列表查询——
 * 等到真有多用户量时换成 MySQL 实现即可（{@link MaterialStore} 是接口，
 * 服务和接口层不用改）。
 *
 * <p>所有路径都过 {@link SafePaths}：{@code userId} 来自请求，
 * 不做清洗就能用 {@code ../../} 把文件写到目录外面。
 */
public class FileSystemMaterialStore implements MaterialStore {

    private static final Logger log = LoggerFactory.getLogger(FileSystemMaterialStore.class);

    private static final String META_FILE = "meta.json";
    private static final String TEXT_FILE = "text.txt";
    private static final String EXTRACTION_FILE = "extraction.json";
    private static final String MATERIALS_DIR = "materials";
    private static final String DEFAULT_USER = "demo-user";

    private final Path root;
    private final ObjectMapper mapper;

    public FileSystemMaterialStore(MaterialModuleProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.root = Path.of(properties.getStorageDir()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建材料存储目录：" + root, e);
        }
        log.info("材料存储目录：{}", root);
    }

    /** 供排查和自检使用。 */
    public Path root() {
        return root;
    }

    @Override
    public Material saveOriginal(Material material, byte[] bytes) {
        Path dir = materialDir(material.userId(), material.id());
        try {
            Files.createDirectories(dir);
            if (bytes != null && bytes.length > 0) {
                Files.write(dir.resolve("original." + TextKit.orEmpty(material.extension())), bytes);
            }
            writeJson(dir.resolve(META_FILE), material);
            return material;
        } catch (IOException e) {
            throw new UncheckedIOException("保存材料失败：" + material.id(), e);
        }
    }

    @Override
    public void saveText(String userId, String materialId, String text) {
        Path dir = materialDir(userId, materialId);
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(TEXT_FILE), TextKit.orEmpty(text), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("保存材料原文失败：" + materialId, e);
        }
    }

    @Override
    public Optional<String> readText(String userId, String materialId) {
        Path file = materialDir(userId, materialId).resolve(TEXT_FILE);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("读取材料原文失败：" + materialId, e);
        }
    }

    @Override
    public void saveExtraction(String userId, String materialId, ExtractedProfile profile) {
        Path dir = materialDir(userId, materialId);
        try {
            Files.createDirectories(dir);
            writeJson(dir.resolve(EXTRACTION_FILE), profile);
        } catch (IOException e) {
            throw new UncheckedIOException("保存抽取结果失败：" + materialId, e);
        }
    }

    @Override
    public Optional<ExtractedProfile> readExtraction(String userId, String materialId) {
        Path file = materialDir(userId, materialId).resolve(EXTRACTION_FILE);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        return readJson(file, ExtractedProfile.class);
    }

    @Override
    public Optional<Material> find(String userId, String materialId) {
        Path file = materialDir(userId, materialId).resolve(META_FILE);
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        return readJson(file, Material.class);
    }

    @Override
    public List<Material> list(String userId) {
        Path dir = materialsDir(userId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        List<Material> materials = new ArrayList<>();
        try (Stream<Path> children = Files.list(dir)) {
            children.filter(Files::isDirectory).forEach(child -> {
                Path meta = child.resolve(META_FILE);
                if (Files.isReadable(meta)) {
                    readJson(meta, Material.class).ifPresent(materials::add);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("列出材料失败", e);
        }
        // 最近上传的在前
        materials.sort(Comparator.comparing(Material::createdAt).reversed());
        return materials;
    }

    @Override
    public boolean delete(String userId, String materialId) {
        Path dir = materialDir(userId, materialId);
        if (!Files.isDirectory(dir)) {
            return false;
        }
        deleteRecursively(dir);
        log.info("已删除材料 {}（用户 {}）", materialId, userId);
        return true;
    }

    // ------------------------------------------------------------------ 内部

    private Path materialsDir(String userId) {
        return SafePaths.resolveInside(root, SafePaths.safeSegment(userId, DEFAULT_USER), MATERIALS_DIR);
    }

    private Path materialDir(String userId, String materialId) {
        return SafePaths.resolveInside(root,
                SafePaths.safeSegment(userId, DEFAULT_USER),
                MATERIALS_DIR,
                SafePaths.safeSegment(materialId, "unknown"));
    }

    private void writeJson(Path file, Object value) throws IOException {
        Files.writeString(file, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value),
                StandardCharsets.UTF_8);
    }

    private <T> Optional<T> readJson(Path file, Class<T> type) {
        try {
            return Optional.of(mapper.readValue(Files.readString(file, StandardCharsets.UTF_8), type));
        } catch (IOException e) {
            // 单个文件损坏不应该让整个列表接口挂掉，记一条日志跳过即可
            log.warn("读取 {} 失败，已跳过：{}", file, e.getMessage());
            return Optional.empty();
        }
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    log.warn("删除 {} 失败：{}", path, e.getMessage());
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("删除材料目录失败：" + dir, e);
        }
    }
}
