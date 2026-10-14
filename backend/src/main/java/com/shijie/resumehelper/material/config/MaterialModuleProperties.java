package com.shijie.resumehelper.material.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 材料模块配置，对应 application.yml 的 {@code app.material.*}。
 */
@ConfigurationProperties(prefix = "app.material")
public class MaterialModuleProperties {

    /** 上传文件、抽出文本、抽取结果的存放目录。相对路径相对于 JVM 工作目录。 */
    private String storageDir = "./data/materials";

    /** 单个文件大小上限（MB）。Spring 的 multipart 限制之外，业务层再挡一道。 */
    private int maxFileSizeMb = 10;

    /** 允许上传的扩展名。 */
    private List<String> allowedExtensions = new ArrayList<>(List.of("pdf", "docx", "txt", "md"));

    /**
     * 扫描件判定阈值：PDF 有页面但可见字符数低于这个值，就认为没有文本层。
     * 不能设成 0——扫描件通常还带着几个字符的页眉或页码。
     */
    private int scannedTextThreshold = 50;

    /** 是否把抽出的原文存盘。存下来才能做「原文对照」和抽取质量排查。 */
    private boolean keepExtractedText = true;

    /** 传给大模型的原文上限（字符）。简历远小于它，属于兜底，防止有人传一本书上来。 */
    private int maxTextChars = 12000;

    public String getStorageDir() {
        return storageDir;
    }

    public void setStorageDir(String storageDir) {
        this.storageDir = storageDir;
    }

    public int getMaxFileSizeMb() {
        return maxFileSizeMb;
    }

    public void setMaxFileSizeMb(int maxFileSizeMb) {
        this.maxFileSizeMb = maxFileSizeMb;
    }

    public List<String> getAllowedExtensions() {
        return allowedExtensions;
    }

    public void setAllowedExtensions(List<String> allowedExtensions) {
        this.allowedExtensions = allowedExtensions;
    }

    public int getScannedTextThreshold() {
        return scannedTextThreshold;
    }

    public void setScannedTextThreshold(int scannedTextThreshold) {
        this.scannedTextThreshold = scannedTextThreshold;
    }

    public boolean isKeepExtractedText() {
        return keepExtractedText;
    }

    public void setKeepExtractedText(boolean keepExtractedText) {
        this.keepExtractedText = keepExtractedText;
    }

    public int getMaxTextChars() {
        return maxTextChars;
    }

    public void setMaxTextChars(int maxTextChars) {
        this.maxTextChars = maxTextChars;
    }

    public long maxFileSizeBytes() {
        return (long) maxFileSizeMb * 1024 * 1024;
    }

    /** 扩展名是否允许（大小写不敏感）。 */
    public boolean allowsExtension(String extension) {
        return extension != null && allowedExtensions.stream().anyMatch(e -> e.equalsIgnoreCase(extension));
    }
}
