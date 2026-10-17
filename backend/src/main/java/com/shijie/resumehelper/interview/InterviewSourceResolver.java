package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.util.TextKit;
import com.shijie.resumehelper.material.service.MaterialService;
import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import com.shijie.resumehelper.resume.service.ResumeService;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/** Resolves an owner-scoped resume source into deterministic interview input. */
public final class InterviewSourceResolver {
    private static final int MAX_TEXT_CHARS = 12_000;

    private final MaterialService materials;
    private final ResumeService resumes;

    public InterviewSourceResolver(MaterialService materials, ResumeService resumes) {
        this.materials = materials;
        this.resumes = resumes;
    }

    public ResolvedSource resolve(String owner, InterviewPlan.ResumeSource source) {
        String label;
        String text;
        if (source.kind() == InterviewPlan.SourceKind.MATERIAL) {
            var detail = materials.find(owner, source.id())
                    .orElseThrow(() -> new InterviewException(404, "找不到这份简历，可能已经被删除了"));
            label = detail.material().fileName();
            text = detail.text();
        } else {
            var version = resumes.findVersion(owner, source.id())
                    .orElseThrow(() -> new InterviewException(404, "找不到这个简历版本，可能已经被删除了"));
            label = version.name();
            text = resumeText(version.draft());
        }
        if (TextKit.isBlank(text)) throw new InterviewException(400, "这份简历没有可用于面试的文字内容");
        if (text.length() > MAX_TEXT_CHARS) throw new InterviewException(400, "简历文字过长，请精简到 12000 字以内");
        return new ResolvedSource(source, label, text, fingerprint(source, text));
    }

    private static String resumeText(ResumeDraft draft) {
        if (draft == null) return "";
        List<String> lines = new ArrayList<>();
        add(lines, draft.headline());
        add(lines, draft.summary());
        appendEntries(lines, "教育经历", draft.educations());
        appendEntries(lines, "工作经历", draft.experiences());
        appendEntries(lines, "项目经历", draft.projects());
        appendSection(lines, "技能", draft.skills());
        appendSection(lines, "关键词", draft.keywords());
        return String.join("\n", lines);
    }

    private static void appendEntries(List<String> lines, String heading, List<ResumeDraft.Entry> entries) {
        if (entries.isEmpty()) return;
        lines.add(heading);
        for (ResumeDraft.Entry entry : entries) {
            String title = entry.title();
            if (!entry.meta().isBlank()) title += (title.isBlank() ? "" : "（") + entry.meta()
                    + (title.isBlank() ? "" : "）");
            add(lines, title);
            entry.bullets().forEach(bullet -> add(lines, "- " + bullet));
        }
    }

    private static void appendSection(List<String> lines, String heading, List<String> values) {
        if (values.isEmpty()) return;
        lines.add(heading);
        values.forEach(value -> add(lines, "- " + value));
    }

    private static void add(List<String> lines, String value) {
        if (!TextKit.isBlank(value)) lines.add(value.strip());
    }

    private static String fingerprint(InterviewPlan.ResumeSource source, String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest((source.kind() + "\n" + source.id()
                    + "\n" + text).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    public record ResolvedSource(InterviewPlan.ResumeSource source, String sourceLabel,
                                 String resumeText, String sourceFingerprint) {}
}
