package com.shijie.resumehelper.resume.domain;

import com.shijie.resumehelper.common.util.TextKit;

/**
 * AI 对单条经历给出的改写建议。对应产品方案 3.B：
 * 「AI 对单条经历提出改写建议，<b>展示原文、建议稿和原因</b>」。
 *
 * <h2>为什么必须同时给出这三样</h2>
 * 只给"建议稿"，用户没法判断改动是好是坏，只能盲选；给了原文和原因，
 * 用户才有依据决定要不要采纳。而且产品原型上明确写着
 * 「AI 建议不会自动覆盖原文」——所以建议永远是**待确认**的状态，
 * 由用户点"采纳"才写进简历。
 *
 * @param section   建议针对哪一段（experiences / projects / educations）
 * @param index     该段里的第几条，用于把建议定位回具体那一行
 * @param original  原文
 * @param suggested 建议稿
 * @param reason    为什么这么改
 */
public record ResumeSuggestion(
        String section,
        int index,
        String original,
        String suggested,
        String reason) {

    public ResumeSuggestion {
        section = TextKit.orEmpty(section);
        original = TextKit.orEmpty(original);
        suggested = TextKit.orEmpty(suggested);
        reason = TextKit.orEmpty(reason);
    }

    /** 建议稿和原文一样（或为空）时没有采纳价值，直接过滤掉。 */
    public boolean isUseful() {
        return !suggested.isBlank() && !suggested.equals(original);
    }
}
