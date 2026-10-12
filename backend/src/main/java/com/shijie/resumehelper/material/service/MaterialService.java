package com.shijie.resumehelper.material.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.material.api.dto.MaterialViews;
import com.shijie.resumehelper.material.api.dto.MaterialViews.ExtractionView;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialDetailView;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialView;
import com.shijie.resumehelper.material.config.MaterialModuleProperties;
import com.shijie.resumehelper.material.domain.ExtractedProfile;
import com.shijie.resumehelper.material.domain.Material;
import com.shijie.resumehelper.material.extract.ProfileExtractor;
import com.shijie.resumehelper.material.parse.DocumentTextExtractor.MaterialParseException;
import com.shijie.resumehelper.material.parse.TextExtraction;
import com.shijie.resumehelper.material.parse.TextExtractorRegistry;
import com.shijie.resumehelper.material.store.MaterialStore;
import com.shijie.resumehelper.material.store.ProfileStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * 材料整理的业务编排：上传 → 抽文本 → 结构化抽取 → 落盘。
 *
 * <h2>一条重要的设计原则：失败的材料也要留下来</h2>
 * 解析失败（扫描件、损坏文件、加密）时我们**照样把它存进去**，状态标成
 * {@code SCANNED} 或 {@code FAILED} 并带上原因。这样用户能在材料列表里看到
 * "哦，原来这份没成功，因为它是扫描件"，然后删掉它或改用粘贴文本。
 *
 * <p>如果直接抛异常、什么都不留，用户只会看到一句"上传失败"，既不知道原因，
 * 也没法管理自己已经传过的东西。
 */
public class MaterialService {

    private static final Logger log = LoggerFactory.getLogger(MaterialService.class);
    private static final String ID_PREFIX = "mat-";

    /** 粘贴文本至少要有的可见字符数。太短的输入抽不出任何东西。 */
    private static final int MIN_PASTE_CHARS = 10;

    private final MaterialStore store;
    private final ProfileStore profileStore;
    private final TextExtractorRegistry extractors;
    private final MaterialModuleProperties properties;

    public MaterialService(MaterialStore store,
                           ProfileStore profileStore,
                           TextExtractorRegistry extractors,
                           MaterialModuleProperties properties) {
        this.store = store;
        this.profileStore = profileStore;
        this.extractors = extractors;
        this.properties = properties;
    }

    /** 上传的输入。刻意不直接用 MultipartFile，让服务层不依赖 Spring web 类型，便于自检。 */
    public record UploadInput(String fileName, String contentType, byte[] bytes) {
    }

    // ------------------------------------------------------------------ 上传

    /**
     * 上传一个文件并抽取。
     *
     * @param aiClient 本次请求的大模型客户端；为 {@code null} 时只抽文本、不做结构化抽取
     */
    public MaterialDetailView upload(String userId, UploadInput input, AiClient aiClient) {
        String extension = extensionOf(input.fileName());
        validate(input, extension);

        String id = newId();
        TextExtraction extraction;
        try {
            extraction = extractors.extract(extension, input.bytes(), input.fileName());
        } catch (MaterialParseException e) {
            // 解析失败：材料照样入库，状态标失败并带上原因，用户可在列表里看到并删除
            log.info("材料解析失败（{}）：{}", input.fileName(), e.getMessage());
            Material failed = new Material(id, userId, input.fileName(), extension, input.contentType(),
                    input.bytes().length, Material.Status.FAILED, 0, 0, e.getMessage(), Instant.now());
            store.saveOriginal(failed, input.bytes());
            return new MaterialDetailView(MaterialView.from(failed, 0), null);
        }

        if (extraction.suspectedScanned()) {
            Material scanned = new Material(id, userId, input.fileName(), extension, input.contentType(),
                    input.bytes().length, Material.Status.SCANNED, extraction.pageCount(), 0,
                    extraction.message(), Instant.now());
            store.saveOriginal(scanned, input.bytes());
            return new MaterialDetailView(MaterialView.from(scanned, 0), null);
        }

        Material material = new Material(id, userId, input.fileName(), extension, input.contentType(),
                input.bytes().length, Material.Status.TEXT_EXTRACTED, extraction.pageCount(),
                extraction.visibleChars(), "", Instant.now());
        store.saveOriginal(material, input.bytes());
        if (properties.isKeepExtractedText()) {
            store.saveText(userId, id, extraction.text());
        }
        return extractAndWrap(userId, material, extraction.text(), aiClient);
    }

    /** 用户直接粘贴一段文本。这条路径**完全不依赖文件解析**，是扫描件的兜底方案。 */
    public MaterialDetailView pasteText(String userId, String title, String rawText, AiClient aiClient) {
        String cleaned = TextKit.cleanExtractedText(rawText);
        if (TextKit.countVisibleChars(cleaned) < MIN_PASTE_CHARS) {
            throw new IllegalArgumentException("粘贴的内容太短了，至少要有 " + MIN_PASTE_CHARS + " 个字。");
        }
        String id = newId();
        String fileName = TextKit.truncate(title, 60) + ".txt";
        Material material = new Material(id, userId, fileName, "txt", "text/plain",
                cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8).length,
                Material.Status.TEXT_EXTRACTED, 0, TextKit.countVisibleChars(cleaned), "", Instant.now());
        store.saveOriginal(material, cleaned.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        if (properties.isKeepExtractedText()) {
            store.saveText(userId, id, cleaned);
        }
        return extractAndWrap(userId, material, cleaned, aiClient);
    }

    private MaterialDetailView extractAndWrap(String userId, Material material, String text, AiClient aiClient) {
        ExtractedProfile profile = new ProfileExtractor(aiClient, extractorMapper(), properties.getMaxTextChars())
                .extract(text, material.fileName());
        store.saveExtraction(userId, material.id(), profile);
        log.info("材料 {} 抽取完成：状态 {}，条目 {} 条，提示 {} 条",
                material.id(), material.status(), profile.itemCount(), profile.warnings().size());
        return new MaterialDetailView(
                MaterialView.from(material, profile.itemCount()),
                ExtractionView.of(material, profile));
    }

    /**
     * {@link ProfileExtractor} 需要 ObjectMapper 来解析模型返回的 JSON。
     *
     * <p>这里单独 new 一个而不是注入 Spring 容器里那个：容器的 ObjectMapper 会根据
     * {@code spring.jackson.*} 做各种定制（比如 {@code NON_NULL}、时间格式），
     * 而模型返回的 JSON 结构完全由提示词决定，用默认配置反而更可预测。
     */
    private ObjectMapper extractorMapper() {
        return new ObjectMapper();
    }

    // ------------------------------------------------------------------ 查询

    public List<MaterialView> list(String userId) {
        List<Material> materials = store.list(userId);
        // 列表要显示"识别出 N 条"，得把每份的抽取结果读出来。材料数量很少，够用。
        return materials.stream().map(material -> {
            int itemCount = store.readExtraction(userId, material.id())
                    .map(ExtractedProfile::itemCount).orElse(0);
            return MaterialView.from(material, itemCount);
        }).toList();
    }

    public Optional<MaterialDetailView> find(String userId, String materialId) {
        return store.find(userId, materialId).map(material -> {
            ExtractedProfile profile = store.readExtraction(userId, materialId).orElse(null);
            return new MaterialDetailView(
                    MaterialView.from(material, profile == null ? 0 : profile.itemCount()),
                    ExtractionView.of(material, profile));
        });
    }

    /** 抽取出的原文，供「原文对照」面板使用。 */
    public Optional<String> originalText(String userId, String materialId) {
        if (store.find(userId, materialId).isEmpty()) {
            return Optional.empty();
        }
        return store.readText(userId, materialId);
    }

    public boolean delete(String userId, String materialId) {
        return store.delete(userId, materialId);
    }

    // ------------------------------------------------------------------ 画像

    public void saveProfile(String userId, UserProfileSnapshot profile) {
        profileStore.save(userId, profile);
        log.info("用户 {} 的求职画像已保存", userId);
    }

    public Optional<UserProfileSnapshot> findProfile(String userId) {
        return profileStore.find(userId);
    }

    // ------------------------------------------------------------------ 校验

    private void validate(UploadInput input, String extension) {
        if (input.bytes() == null || input.bytes().length == 0) {
            throw new IllegalArgumentException("文件是空的，请重新选择。");
        }
        if (input.bytes().length > properties.maxFileSizeBytes()) {
            throw new IllegalArgumentException("文件超过了 " + properties.getMaxFileSizeMb()
                    + " MB 的限制，请压缩后再上传，或者用「粘贴文本」。");
        }
        if (!properties.allowsExtension(extension)) {
            throw new IllegalArgumentException("不支持 " + describeExtension(extension) + " 格式。"
                    + "目前支持 " + String.join("、", extractors.supportedExtensions()).toUpperCase(Locale.ROOT)
                    + "，也可以直接用「粘贴文本」。"
                    + ("doc".equals(extension) ? "（旧版 .doc 请先用 Word 另存为 .docx）" : ""));
        }
    }

    private String describeExtension(String extension) {
        return extension.isEmpty() ? "这种" : "." + extension;
    }

    /** 从文件名取扩展名。取不到就返回空串，由校验环节给出可读的提示。 */
    static String extensionOf(String fileName) {
        String name = TextKit.orEmpty(fileName).trim();
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private String newId() {
        return ID_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
