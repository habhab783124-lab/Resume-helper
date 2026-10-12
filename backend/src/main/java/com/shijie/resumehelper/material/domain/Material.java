package com.shijie.resumehelper.material.domain;

import com.shijie.resumehelper.common.util.TextKit;

import java.time.Instant;

/**
 * 一份上传的求职材料。对应产品方案 7 的 {@code Material}：
 * 「上传文件、解析状态和提取文本」。
 *
 * <p><b>注意：这里刻意不持有抽出的原文</b>。原文可能几千字，塞进这个对象会让它到处被搬运、
 * 也会让日志和接口响应变重。原文由 {@code MaterialStore} 单独按 id 存成文本文件，
 * 需要「原文对照」时用 {@code GET /api/materials/{id}/text} 取。
 *
 * @param id          材料 id
 * @param userId      所属用户，用于多用户隔离（当前演示阶段前端传固定值）
 * @param fileName    用户上传时的原始文件名，展示用
 * @param extension   归一化后的扩展名：pdf / docx / txt / md
 * @param contentType 浏览器上报的 MIME，仅作记录
 * @param sizeBytes   文件大小
 * @param status      解析状态
 * @param pageCount   PDF 的页数；非 PDF 为 0
 * @param textLength  抽出的可见字符数
 * @param message     给用户看的说明（扫描件提示、失败原因等）
 * @param createdAt   上传时间
 */
public record Material(
        String id,
        String userId,
        String fileName,
        String extension,
        String contentType,
        long sizeBytes,
        Status status,
        int pageCount,
        int textLength,
        String message,
        Instant createdAt) {

    /**
     * 材料解析状态。
     *
     * <p>目前是<b>同步抽取</b>（一份简历通常几百毫秒就抽完），所以接口返回时状态已经确定，
     * 不会出现中间的「处理中」。保留这个字段是为了将来换成异步（大文件、OCR）时不用改接口。
     */
    public enum Status {
        /** 成功抽出文本，可以拿去做结构化抽取 */
        TEXT_EXTRACTED,
        /** 文件里没有文本层（扫描件/图片），需要用户改用「粘贴文本」 */
        SCANNED,
        /** 解析失败：文件损坏、加密、格式不支持等 */
        FAILED
    }

    public Material {
        id = TextKit.orEmpty(id);
        userId = TextKit.isBlank(userId) ? "demo-user" : userId.trim();
        fileName = TextKit.isBlank(fileName) ? "未命名文件" : fileName.trim();
        extension = TextKit.orEmpty(extension).toLowerCase();
        contentType = TextKit.orEmpty(contentType);
        message = TextKit.orEmpty(message);
        status = status == null ? Status.FAILED : status;
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** 能否进入下一步（结构化抽取）。 */
    public boolean isUsable() {
        return status == Status.TEXT_EXTRACTED && textLength > 0;
    }

    /** 给前端展示的大小，例如 "1.8 MB"。 */
    public String readableSize() {
        if (sizeBytes < 1024) {
            return sizeBytes + " B";
        }
        if (sizeBytes < 1024 * 1024) {
            return String.format("%.0f KB", sizeBytes / 1024.0);
        }
        return String.format("%.1f MB", sizeBytes / (1024.0 * 1024));
    }

    /** 文件类型标签，对应原型里的 file-pdf / file-doc 样式。 */
    public String fileTypeLabel() {
        return switch (extension) {
            case "pdf" -> "PDF";
            case "docx" -> "DOCX";
            case "md" -> "MD";
            default -> "TXT";
        };
    }
}
