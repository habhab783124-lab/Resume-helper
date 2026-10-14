package com.shijie.resumehelper.resume.domain;

import com.shijie.resumehelper.common.util.TextKit;

import java.time.Instant;

/**
 * 保存下来的一版简历。对应产品方案 3.B：「用户确认后保存为新版本」。
 *
 * <p>为什么要有"版本"而不是只存一份：简历是要按岗位改的。用户投 Java 后端和投
 * 数据开发，同一份经历该强调的东西不一样。保留版本就能"投 A 岗用 1 号版本、
 * 投 B 岗用 2 号版本"，也能在改坏之后退回去。
 *
 * @param id          版本 id
 * @param userId      所属用户
 * @param name        版本名，默认用工种 + 时间，用户可改
 * @param targetTitle 这一版针对的目标职位
 * @param targetJd    这一版参考过的岗位描述（可以为空；有它才能解释"为什么这么改"）
 * @param draft       简历内容
 * @param createdAt   保存时间
 */
public record ResumeVersion(
        String id,
        String userId,
        String name,
        String targetTitle,
        String targetJd,
        ResumeDraft draft,
        Instant createdAt) {

    public ResumeVersion {
        id = TextKit.orEmpty(id);
        userId = TextKit.isBlank(userId) ? "demo-user" : userId.trim();
        name = TextKit.isBlank(name) ? "未命名版本" : name.trim();
        targetTitle = TextKit.trim(targetTitle);
        targetJd = TextKit.orEmpty(targetJd);
        createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    /** 列表里显示的摘要：有多少条经历、多少技能。 */
    public String summaryText() {
        if (draft == null) {
            return "空简历";
        }
        return "教育 " + draft.educations().size()
                + " · 经历 " + draft.experiences().size()
                + " · 项目 " + draft.projects().size()
                + " · 技能 " + draft.skills().size();
    }
}
