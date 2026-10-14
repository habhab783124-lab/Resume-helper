package com.shijie.resumehelper.material.parse;

import com.shijie.resumehelper.common.util.TextKit;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 从 .docx 里抽文本，用 Apache POI。
 *
 * <h2>为什么用 getBodyElements() 而不是分别遍历段落和表格</h2>
 * 很多简历用表格排版（左边"教育经历"标题、右边内容，或者整页就是一个大表格）。
 * 如果先输出所有段落、再输出所有表格，抽取结果的**顺序就乱了**——
 * 「教育经历」的标题会跑到项目经历下面，大模型读起来就会张冠李戴。
 *
 * <p>{@code getBodyElements()} 按文档的真实顺序返回段落和表格，
 * 顺序保住了，结构信息也就保住了。
 *
 * <p>旧版 {@code .doc}（二进制格式）不在这里处理：它需要额外的 poi-scratchpad 依赖，
 * 而且现在几乎没人用。上层会明确提示用户"另存为 .docx"。
 */
public final class DocxTextExtractor implements DocumentTextExtractor {

    /** 压缩包里最多允许的条目数。正常 docx 几十个，超过基本可以判定异常。 */
    private static final int MAX_ZIP_ENTRIES = 200;

    /** 解压后最多允许多少字节。正常简历的 docx 解压后也就几百 KB。 */
    private static final long MAX_UNCOMPRESSED_BYTES = 10L * 1024 * 1024;

    @Override
    public boolean supports(String extension) {
        return "docx".equalsIgnoreCase(extension);
    }

    @Override
    public String formatName() {
        return "DOCX";
    }

    @Override
    public TextExtraction extract(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new MaterialParseException(fileName, "文件是空的，请重新上传。");
        }
        checkZipBomb(bytes, fileName);
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            StringBuilder sb = new StringBuilder();

            for (IBodyElement element : document.getBodyElements()) {
                if (element instanceof XWPFParagraph paragraph) {
                    appendParagraph(sb, paragraph);
                } else if (element instanceof XWPFTable table) {
                    appendTable(sb, table);
                }
            }

            String text = TextKit.cleanExtractedText(sb.toString());
            if (TextKit.countVisibleChars(text) == 0) {
                throw new MaterialParseException(fileName,
                        "这个 Word 文档里没有可读取的文字（可能是纯图片排版）。请用「粘贴文本」把内容贴进来。");
            }
            // docx 没有页数概念
            return TextExtraction.success(text, 0);
        } catch (EncryptedDocumentException e) {
            throw new MaterialParseException(fileName,
                    "这个 Word 文档设了密码，读不了。请先取消密码保护再上传。", e);
        } catch (IOException e) {
            throw new MaterialParseException(fileName,
                    "Word 文档解析失败，文件可能已损坏。可以试试用「粘贴文本」代替。", e);
        } catch (RuntimeException e) {
            // POI 对畸形文件也会抛各种运行时异常，统一收成人话
            throw new MaterialParseException(fileName,
                    "Word 文档解析失败，文件可能已损坏。可以试试用「粘贴文本」代替。", e);
        }
    }

    private void appendParagraph(StringBuilder sb, XWPFParagraph paragraph) {        String line = paragraph.getText();
        if (line != null && !line.isBlank()) {
            sb.append(line).append('\n');
        }
    }

    /**
     * 表格按行输出，同一行的单元格用 {@code |} 连接。
     *
     * <p>保留行结构很重要：简历表格里"公司名 | 职位 | 时间"往往就是一行三个单元格，
     * 用分隔符连起来大模型才看得懂它们属于同一条经历。
     */
    private void appendTable(StringBuilder sb, XWPFTable table) {
        for (XWPFTableRow row : table.getRows()) {
            StringBuilder line = new StringBuilder();
            for (XWPFTableCell cell : row.getTableCells()) {
                String cellText = TextKit.cleanExtractedText(cell.getText()).replace("\n", " ").trim();
                if (cellText.isEmpty()) {
                    continue;
                }
                if (line.length() > 0) {
                    line.append(" | ");
                }
                line.append(cellText);
            }
            if (line.length() > 0) {
                sb.append(line).append('\n');
            }
        }
    }

    /**
     * 防「压缩炸弹」。
     *
     * <p>.docx 本质是一个 zip：把几 KB 的压缩包解开可能是几 GB。
     * 攻击者（或者只是手滑传了个奇怪文件）可以用这种方式把服务的内存吃光——
     * POI 在解析时会把解压后的内容读进内存，所以**必须在交给 POI 之前**先自己数一遍。
     *
     * <p>这段逻辑来自队友在面试模块里写的版本，统一材料模块时特意保留了下来。
     */
    private void checkZipBomb(byte[] bytes, String fileName) {
        int entries = 0;
        long total = 0;
        byte[] chunk = new byte[8192];
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new MaterialParseException(fileName,
                            "这个 Word 文档内部结构过于复杂，读不了。可以用「粘贴文本」把内容贴进来。");
                }
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_UNCOMPRESSED_BYTES) {
                        throw new MaterialParseException(fileName,
                                "这个 Word 文档解压后的内容过大，读不了。请精简后重新上传，或改用「粘贴文本」。");
                    }
                }
            }
        } catch (IOException e) {
            // 连 zip 都读不出来，说明不是有效的 docx；交给下面 POI 那一步给出更准确的提示
            return;
        } catch (MaterialParseException e) {
            throw e;
        }
    }
}
