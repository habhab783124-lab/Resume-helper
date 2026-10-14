package com.shijie.resumehelper.material.parse;

import java.util.List;

/**
 * 按扩展名挑一个 {@link DocumentTextExtractor}。
 *
 * <p>Spring 会把所有 {@code DocumentTextExtractor} Bean 注入进来，
 * 所以新增格式只要实现接口 + 加 {@code @Bean}，这里不用改（和岗位模块的
 * {@code JobSourceRegistry} 是同一套思路）。
 */
public final class TextExtractorRegistry {

    private final List<DocumentTextExtractor> extractors;

    public TextExtractorRegistry(List<DocumentTextExtractor> extractors) {
        this.extractors = List.copyOf(extractors == null ? List.of() : extractors);
    }

    public List<DocumentTextExtractor> extractors() {
        return extractors;
    }

    /** 是否支持这个扩展名。用于上传时提前拒绝，而不是收了文件再报错。 */
    public boolean supports(String extension) {
        return find(extension) != null;
    }

    /** 支持哪些扩展名，用于错误提示。 */
    public List<String> supportedExtensions() {
        return List.of("pdf", "docx", "txt", "md");
    }

    public TextExtraction extract(String extension, byte[] bytes, String fileName) {
        DocumentTextExtractor extractor = find(extension);
        if (extractor == null) {
            throw new DocumentTextExtractor.MaterialParseException(fileName,
                    "暂不支持 " + describe(extension) + " 格式。"
                            + "目前支持 PDF、DOCX、TXT、Markdown，或者直接用「粘贴文本」把内容贴进来。");
        }
        return extractor.extract(bytes, fileName);
    }

    private DocumentTextExtractor find(String extension) {
        if (extension == null || extension.isBlank()) {
            return null;
        }
        for (DocumentTextExtractor extractor : extractors) {
            if (extractor.supports(extension)) {
                return extractor;
            }
        }
        return null;
    }

    private String describe(String extension) {
        return extension == null || extension.isBlank() ? "这种" : "." + extension;
    }
}
