package com.shijie.resumehelper.material.parse;

import com.shijie.resumehelper.common.util.TextKit;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.IOException;

/**
 * 从 PDF 里抽文本，用 Apache PDFBox。
 *
 * <p>只处理**有文字层**的 PDF。扫描件/拍照导出（整页就是一张图）抽出来是空的，
 * 这种情况我们<b>不报错误</b>，而是返回 {@link TextExtraction#scanned}，
 * 让界面提示用户改用粘贴文本——因为那是文件的问题，不是产品坏了。
 *
 * <p>{@code setSortByPosition(true)} 是为了双栏简历：PDF 里文字是按绘制顺序存的，
 * 不按位置排序的话左右栏会交错在一起，读起来是乱码。开了之后按坐标排，左右栏能分开。
 */
public final class PdfTextExtractor implements DocumentTextExtractor {

    private final int scannedThreshold;

    /** 页数上限。简历不该有这么长，超了基本是传错文件。 */
    private static final int MAX_PAGES = 30;

    /**
     * @param scannedThreshold 可见字符数低于这个值就判定为扫描件。
     *                         阈值不能是 0：有些扫描件会带几个字符的页眉/页码。
     */
    public PdfTextExtractor(int scannedThreshold) {
        this.scannedThreshold = Math.max(1, scannedThreshold);
    }

    @Override
    public boolean supports(String extension) {
        return "pdf".equalsIgnoreCase(extension);
    }

    @Override
    public String formatName() {
        return "PDF";
    }

    @Override
    public TextExtraction extract(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length == 0) {
            throw new MaterialParseException(fileName, "文件是空的，请重新上传。");
        }
        try (PDDocument document = Loader.loadPDF(bytes)) {
            int pages = document.getNumberOfPages();

            // 页数上限：传错文件（比如整本手册）时早点拒绝，不用白读一遍再交给大模型
            if (pages > MAX_PAGES) {
                throw new MaterialParseException(fileName,
                        "这份 PDF 有 " + pages + " 页，超过了 " + MAX_PAGES + " 页的限制。"
                                + "简历通常不超过 3 页，请确认是不是传错文件了。");
            }

            // 作者权限：有些 PDF 明确禁止提取文本。这时读出来是空的，
            // 不检查就会被误判成「扫描件」，给出错误的建议。
            if (!document.getCurrentAccessPermission().canExtractContent()) {
                throw new MaterialParseException(fileName,
                        "这份 PDF 的作者设置了「禁止提取文本」的权限，读不了内容。"
                                + "可以改用「粘贴文本」把简历贴进来。");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = TextKit.cleanExtractedText(stripper.getText(document));

            if (TextKit.countVisibleChars(text) < scannedThreshold) {
                return TextExtraction.scanned(pages);
            }
            return TextExtraction.success(text, pages);
        } catch (InvalidPasswordException e) {
            throw new MaterialParseException(fileName,
                    "这份 PDF 设了密码，读不了。请先取消密码保护再上传（也可以用「粘贴文本」）。", e);
        } catch (IOException e) {
            throw new MaterialParseException(fileName,
                    "PDF 解析失败，文件可能已损坏。可以试试用「粘贴文本」代替。", e);
        } catch (RuntimeException e) {
            // PDFBox 在遇到畸形文件时会抛各种运行时异常（数组越界、解析异常等），
            // 统一收成一句人话，不要让堆栈冒到接口层
            throw new MaterialParseException(fileName,
                    "PDF 解析失败，文件可能已损坏。可以试试用「粘贴文本」代替。", e);
        }
    }
}
