package com.shijie.resumehelper.material.parse;

import com.shijie.resumehelper.common.util.TextKit;

/**
 * 从文档里抽文本的结果。
 *
 * @param text            抽出的文本（已清洗，保留换行结构）
 * @param pageCount       页数；非 PDF 为 0
 * @param suspectedScanned 是否疑似扫描件（没有文本层）
 * @param message         给用户看的说明
 */
public record TextExtraction(String text, int pageCount, boolean suspectedScanned, String message) {

    public TextExtraction {
        text = TextKit.orEmpty(text);
        pageCount = Math.max(0, pageCount);
        message = TextKit.orEmpty(message);
    }

    /** 可见字符数（不算空白）。 */
    public int visibleChars() {
        return TextKit.countVisibleChars(text);
    }

    public static TextExtraction success(String text, int pageCount) {
        return new TextExtraction(text, pageCount, false, "");
    }

    /**
     * 疑似扫描件。
     *
     * <p>为什么不直接报「解析失败」：失败会让人以为产品坏了。
     * 实际情况是这份 PDF 是扫描/拍照来的，里面没有文字层——这是**文件的问题，不是产品的问题**，
     * 所以要给出可执行的建议（改用粘贴文本），而不是一句错误。
     */
    public static TextExtraction scanned(int pageCount) {
        return new TextExtraction("", pageCount, true,
                "这份 PDF 里没有文字层（应该是扫描件或截图导出的），无法直接提取文字。"
                        + "请改用下方的「粘贴文本」，把简历内容贴进来，效果是一样的。");
    }
}
