package com.shijie.resumehelper.material.parse;

/**
 * 从一种文档格式里抽文本。每支持一种格式就实现一个。
 *
 * <p>和 {@code JobSource} 一样是 SPI：新增格式（比如以后要支持 .doc、.rtf、.html）
 * 只要实现本接口并注册成 Bean，{@link TextExtractorRegistry} 会自动发现，
 * 上层的材料服务和接口都不用改。
 *
 * <p>实现约定：
 * <ul>
 *   <li>抽不出文本要抛 {@link MaterialParseException} 并带上**用户看得懂**的原因；</li>
 *   <li>文件加密、损坏、格式不符都算解析失败，不要让异常直接冒到接口层；</li>
 *   <li>返回的文本要做清洗（见 {@code TextKit.cleanExtractedText}），但保留换行结构。</li>
 * </ul>
 */
public interface DocumentTextExtractor {

    /** 是否支持这个扩展名（小写，不带点）。 */
    boolean supports(String extension);

    /** 展示用的格式名，例如 "PDF"。 */
    default String formatName() {
        return "文档";
    }

    /**
     * 抽取文本。
     *
     * @param bytes    文件内容
     * @param fileName 原始文件名，只用于错误信息
     */
    TextExtraction extract(byte[] bytes, String fileName);

    /** 解析失败。消息会原样展示给用户，所以必须是人话。 */
    class MaterialParseException extends RuntimeException {

        private final String fileName;

        public MaterialParseException(String fileName, String message) {
            super(message);
            this.fileName = fileName;
        }

        public MaterialParseException(String fileName, String message, Throwable cause) {
            super(message, cause);
            this.fileName = fileName;
        }

        public String fileName() {
            return fileName;
        }
    }
}
