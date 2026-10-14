package com.shijie.resumehelper.resume.api.dto;

import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeSuggestion;
import com.shijie.resumehelper.resume.domain.ResumeVersion;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 简历接口的请求 / 响应体。
 */
public final class ResumeViews {

    private ResumeViews() {
    }

    /**
     * 生成出来的简历内容。
     *
     * @param empty   画像里什么都没有时为 true，页面据此提示"先去材料整理"
     * @param message 给用户看的说明（空简历、没有 token 等），正常时为空串
     */
    public record DraftView(ResumeDraft draft, boolean empty, String message) {
    }

    /**
     * 请求改写建议。
     *
     * @param targetTitle 目标职位
     * @param jd          岗位描述（可空）
     * @param draft       前端当前屏幕上的简历内容。带上它，建议针对的就是"改到一半的这一份"；
     *                    不传则用后端按画像重新生成的那一份。
     */
    public record SuggestRequest(String targetTitle, String jd, ResumeDraft draft) {
    }

    /** {@code message} 非空时表示本次没给出建议，并说明原因。 */
    public record SuggestView(List<ResumeSuggestion> suggestions, String message) {
    }

    public record SaveVersionRequest(String name, String targetTitle, String jd, ResumeDraft draft) {
    }

    /** 列表用的精简版本（不带整份简历内容，列表不需要那么重）。 */
    public record VersionView(
            String id,
            String name,
            String targetTitle,
            boolean hasJd,
            String summaryText,
            String createdAt) {

        public static VersionView from(ResumeVersion version) {
            return new VersionView(
                    version.id(),
                    version.name(),
                    version.targetTitle(),
                    !version.targetJd().isBlank(),
                    version.summaryText(),
                    formatTime(version.createdAt()));
        }
    }

    /** 详情：版本信息 + 完整简历内容，用于"载入这一版接着改"。 */
    public record VersionDetailView(VersionView version, ResumeDraft draft) {
    }

    private static String formatTime(Instant instant) {
        if (instant == null) {
            return "";
        }
        ZoneId zone = ZoneId.systemDefault();
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(instant.atZone(zone));
    }
}
