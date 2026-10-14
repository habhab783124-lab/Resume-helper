package com.shijie.resumehelper.material.parse;

import com.shijie.resumehelper.common.util.TextKit;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * 纯文本 / Markdown。
 *
 * <h2>为什么要做编码探测</h2>
 * 中文 Windows 上「记事本另存为」默认存的是 <b>GBK</b>，不是 UTF-8。
 * 用户把一个从记事本导出的简历存成 .txt 传上来，如果直接按 UTF-8 读，
 * 得到的是满屏 `` 乱码——然后大模型就会基于乱码抽出一堆垃圾，
 * 用户看到的结果是"AI 把我简历读错了"，但根因只是编码。
 *
 * <p>所以这里先**严格**按 UTF-8 解码，失败就退回 GBK。
 * 严格解码用 {@link CodingErrorAction#REPORT}，因为宽松模式不会报错，
 * 只会把非法字节换成 U+FFFD，那样就探测不出来了。
 */
public final class PlainTextExtractor implements DocumentTextExtractor {

    private static final List<String> SUPPORTED = List.of("txt", "text", "md", "markdown");

    @Override
    public boolean supports(String extension) {
        return extension != null && SUPPORTED.contains(extension.toLowerCase());
    }

    @Override
    public String formatName() {
        return "纯文本";
    }

    @Override
    public TextExtraction extract(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new MaterialParseException(fileName, "文件是空的，请重新上传。");
        }
        String cleaned = TextKit.cleanExtractedText(decode(bytes));
        if (TextKit.countVisibleChars(cleaned) == 0) {
            throw new MaterialParseException(fileName, "文件里没有可读取的文字。");
        }
        return TextExtraction.success(cleaned, 0);
    }

    private String decode(byte[] bytes) {
        int offset = bomLength(bytes);
        int length = bytes.length - offset;
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, offset, length))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            // 不是合法 UTF-8，大概率是 GBK（中文 Windows 记事本的默认编码）
            try {
                return new String(bytes, offset, length, Charset.forName("GBK"));
            } catch (RuntimeException unsupported) {
                // 极端情况下 JVM 没有 GBK：退回 Latin-1，至少不丢字节
                return new String(bytes, offset, length, StandardCharsets.ISO_8859_1);
            }
        }
    }

    /** 跳过 UTF-8 BOM，否则第一个字段前面会挂一个看不见的字符，抽取时容易出错。 */
    private int bomLength(byte[] bytes) {
        if (bytes.length >= 3
                && (bytes[0] & 0xFF) == 0xEF
                && (bytes[1] & 0xFF) == 0xBB
                && (bytes[2] & 0xFF) == 0xBF) {
            return 3;
        }
        return 0;
    }
}
