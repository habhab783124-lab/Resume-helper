package com.shijie.resumehelper.material.api.dto;

import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.material.domain.ExtractedProfile;
import com.shijie.resumehelper.material.domain.Material;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 材料相关接口的响应体。
 *
 * <p>字段命名刻意对齐前端原型 {@code prototype-static} 里已有的 DOM：
 * 卡片显示文件名、类型标签、大小、状态胶囊，所以这里直接给出
 * {@code fileTypeLabel}、{@code sizeText}、{@code statusLabel}、{@code statusStyle}，
 * 前端不用在模板里写一堆 if 去翻译状态。
 */
public final class MaterialViews {

    private MaterialViews() {
    }

    /**
     * 材料的展示视图。
     *
     * @param status      机器可读状态：TEXT_EXTRACTED / SCANNED / FAILED
     * @param statusLabel 给用户看的状态文案
     * @param statusStyle 对应的样式后缀，前端拼成 {@code status-pill status-<style>}
     * @param itemCount   识别出的条目数（教育+实习+项目+技能+获奖）
     */
    public record MaterialView(
            String id,
            String fileName,
            String extension,
            String fileTypeLabel,
            String sizeText,
            long sizeBytes,
            String status,
            String statusLabel,
            String statusStyle,
            int pageCount,
            int textLength,
            int itemCount,
            String message,
            String createdAt,
            boolean hasText,
            boolean hasExtraction) {

        public static MaterialView from(Material material, int itemCount) {
            return new MaterialView(
                    material.id(),
                    material.fileName(),
                    material.extension(),
                    material.fileTypeLabel(),
                    material.readableSize(),
                    material.sizeBytes(),
                    material.status().name(),
                    // 必须写全 MaterialViews. 前缀：record 自己的 statusLabel() 访问器
                    // 会把外层类的同名静态方法遮蔽掉，不加限定编译不过
                    MaterialViews.statusLabel(material.status()),
                    MaterialViews.statusStyle(material.status()),
                    material.pageCount(),
                    material.textLength(),
                    itemCount,
                    material.message(),
                    MaterialViews.formatTime(material.createdAt()),
                    material.textLength() > 0,
                    itemCount > 0);
        }
    }

    /** 抽取结果视图。{@code profile} 直接复用领域对象，因为它的 evidence 字段就是给前端看的。 */
    public record ExtractionView(
            String materialId,
            String fileName,
            ExtractedProfile profile,
            int itemCount) {

        public static ExtractionView of(Material material, ExtractedProfile profile) {
            if (profile == null) {
                return null;
            }
            return new ExtractionView(material.id(), material.fileName(), profile, profile.itemCount());
        }
    }

    /**
     * 材料详情：元数据 + 抽取结果 + 抽出的原文。
     *
     * <p>{@code text} 也在这里返回一份：面试模块需要「materialId + 简历正文」两样东西
     * 才能开始面试，放在同一个响应里它就不用发两次请求。
     * <b>列表接口不带 text</b>，避免把一堆简历正文一次性搬出来。
     *
     * <p>{@code GET /api/materials/{id}/text} 仍然保留：原文对照面板要的是
     * {@code text/plain}，直接渲染最省事。
     */
    public record MaterialDetailView(MaterialView material, ExtractionView extraction, String text) {
    }

    /** 列表用的批量转换。 */
    public static List<MaterialView> listOf(List<Material> materials, java.util.function.ToIntFunction<Material> itemCounter) {
        return materials.stream().map(m -> MaterialView.from(m, itemCounter.applyAsInt(m))).toList();
    }

    private static String statusLabel(Material.Status status) {
        return switch (status) {
            case TEXT_EXTRACTED -> "已提取";
            case SCANNED -> "需粘贴文本";
            case FAILED -> "解析失败";
        };
    }

    private static String statusStyle(Material.Status status) {
        return switch (status) {
            case TEXT_EXTRACTED -> "ready";
            case SCANNED -> "warn";
            case FAILED -> "error";
        };
    }

    /**
     * 时间展示对齐原型的写法：「今天 09:42」「昨天 16:18」「2026-03-12 10:05」。
     * 比一串 ISO 时间戳可读得多，也省得前端再引一个日期库。
     */
    private static String formatTime(Instant instant) {
        if (instant == null) {
            return "";
        }
        ZoneId zone = ZoneId.systemDefault();
        LocalDate date = instant.atZone(zone).toLocalDate();
        LocalDate today = LocalDate.now(zone);
        String time = DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(zone));
        if (date.equals(today)) {
            return "今天 " + time;
        }
        if (date.equals(today.minusDays(1))) {
            return "昨天 " + time;
        }
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(instant.atZone(zone));
    }

    /** 粘贴文本接口的请求体。 */
    public record PasteTextRequest(String title, String text) {
        public String safeTitle() {
            return TextKit.isBlank(title) ? "粘贴的文本" : title.trim();
        }
    }
}
