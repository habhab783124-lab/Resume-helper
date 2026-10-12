package com.shijie.resumehelper.material;

import com.shijie.resumehelper.material.parse.DocumentTextExtractor;
import com.shijie.resumehelper.material.parse.DocumentTextExtractor.MaterialParseException;
import com.shijie.resumehelper.material.parse.DocxTextExtractor;
import com.shijie.resumehelper.material.parse.PdfTextExtractor;
import com.shijie.resumehelper.material.parse.PlainTextExtractor;
import com.shijie.resumehelper.material.parse.TextExtraction;
import com.shijie.resumehelper.material.parse.TextExtractorRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 文本抽取测试，**用真实的简历文件**跑，不是拼字符串。
 *
 * <p>样本的生成方式和为什么需要它们，见 {@code tools/testdata/make-samples.py}。
 * 样本不存在时这些测试会跳过（同事没装 Python/LibreOffice 也能跑 mvn test），
 * 但那样就等于没验证——所以 CI 和验收时应该确保样本存在。
 */
class TextExtractionTest {

    private static final Path TESTDATA = Path.of("tools", "testdata");
    private static final int SCANNED_THRESHOLD = 50;

    private static byte[] sample(String name) throws Exception {
        Path path = TESTDATA.resolve(name);
        assumeTrue(Files.exists(path),
                "缺少测试样本 " + name + "，先运行：python tools/testdata/make-samples.py");
        return Files.readAllBytes(path);
    }

    @Test
    @DisplayName("DOCX：表格与段落保持文档顺序，表格用 | 连接单元格")
    void docxKeepsDocumentOrder() throws Exception {
        byte[] bytes = sample("sample-resume.docx");

        TextExtraction result = new DocxTextExtractor().extract(bytes, "sample-resume.docx");

        assertThat(result.suspectedScanned()).isFalse();
        assertThat(result.visibleChars()).isGreaterThan(500);

        String text = result.text();
        int educationHeading = text.indexOf("教育经历");
        int school = text.indexOf("上海理工大学");
        int internshipHeading = text.indexOf("实习经历");

        assertThat(educationHeading).isGreaterThanOrEqualTo(0);
        assertThat(school).isGreaterThan(educationHeading);
        // 这条是这个测试的重点：如果先输出全部段落、再输出全部表格，
        // "实习经历" 就会跑到表格前面，大模型会把教育经历归到实习名下
        assertThat(internshipHeading).isGreaterThan(school);

        // 表格单元格保留分隔符，"学校|专业|学历|时间" 才不会被粘成一个词
        assertThat(text).contains("|");
        assertThat(text).contains("上海理工大学");
        assertThat(text).contains("2022.09 - 2026.06");
    }

    @Test
    @DisplayName("PDF：有文字层时正常抽出，并报告页数")
    void pdfWithTextLayerIsExtracted() throws Exception {
        byte[] bytes = sample("sample-resume.pdf");

        TextExtraction result = new PdfTextExtractor(SCANNED_THRESHOLD)
                .extract(bytes, "sample-resume.pdf");

        assertThat(result.suspectedScanned()).isFalse();
        assertThat(result.pageCount()).isGreaterThan(0);
        assertThat(result.visibleChars()).isGreaterThan(500);
        assertThat(result.text()).contains("上海理工大学");
        assertThat(result.text()).contains("Spring Boot");
    }

    @Test
    @DisplayName("扫描件 PDF：识别为『需粘贴文本』而不是当成解析失败")
    void scannedPdfIsDetectedNotFailed() throws Exception {
        byte[] bytes = sample("sample-scanned.pdf");

        TextExtraction result = new PdfTextExtractor(SCANNED_THRESHOLD)
                .extract(bytes, "sample-scanned.pdf");

        // 关键行为：不抛异常。抛异常会让用户以为产品坏了，
        // 实际是这份文件没有文字层，属于文件本身的问题。
        assertThat(result.suspectedScanned()).isTrue();
        assertThat(result.visibleChars()).isZero();
        assertThat(result.message()).contains("粘贴文本");
        assertThat(result.pageCount()).isGreaterThan(0);
    }

    @Test
    @DisplayName("损坏的文件给出可读提示，而不是把堆栈抛给用户")
    void corruptFileGivesReadableMessage() {
        byte[] garbage = "这不是一个真正的 PDF 文件".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> new PdfTextExtractor(SCANNED_THRESHOLD).extract(garbage, "broken.pdf"))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("解析失败");

        assertThatThrownBy(() -> new DocxTextExtractor().extract(garbage, "broken.docx"))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("解析失败");
    }

    @Test
    @DisplayName("不支持的格式由注册表统一拒绝，并告诉用户能用什么")
    void unsupportedFormatIsRejectedWithGuidance() {
        TextExtractorRegistry registry = new TextExtractorRegistry(
                List.of(new PdfTextExtractor(SCANNED_THRESHOLD), new DocxTextExtractor(), new PlainTextExtractor()));

        assertThat(registry.supports("pdf")).isTrue();
        assertThat(registry.supports("docx")).isTrue();
        assertThat(registry.supports("doc")).isFalse();

        assertThatThrownBy(() -> registry.extract("doc", new byte[] {1, 2, 3}, "老简历.doc"))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("PDF")
                .hasMessageContaining("粘贴文本");
    }

    @Test
    @DisplayName("纯文本：GBK 编码也能正确读出（中文 Windows 记事本的默认编码）")
    void plainTextFallsBackToGbk() {
        String content = "林一舟\r\n求职意向：Java 后端开发工程师\r\n教育经历\r\n上海理工大学 计算机科学与技术";
        byte[] gbk = content.getBytes(Charset.forName("GBK"));

        TextExtraction result = new PlainTextExtractor().extract(gbk, "resume.txt");

        // 如果按 UTF-8 硬读，这里会是满屏乱码，然后大模型基于乱码抽出一堆垃圾
        assertThat(result.text()).contains("林一舟");
        assertThat(result.text()).contains("上海理工大学");
        assertThat(result.text()).doesNotContain("\uFFFD");
    }

    @Test
    @DisplayName("纯文本：UTF-8 BOM 会被去掉，不会污染第一个字段")
    void plainTextStripsUtf8Bom() {
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] content = "林一舟的简历".getBytes(StandardCharsets.UTF_8);
        byte[] withBom = new byte[bom.length + content.length];
        System.arraycopy(bom, 0, withBom, 0, bom.length);
        System.arraycopy(content, 0, withBom, bom.length, content.length);

        TextExtraction result = new PlainTextExtractor().extract(withBom, "resume.txt");

        assertThat(result.text()).startsWith("林一舟");
    }

    @Test
    @DisplayName("空白文件被拒绝，而不是抽出一个空结果让大模型去编")
    void emptyFileIsRejected() {
        assertThatThrownBy(() -> new PlainTextExtractor().extract("   \n  ".getBytes(StandardCharsets.UTF_8), "empty.txt"))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("没有可读取的文字");

        assertThatThrownBy(() -> new PlainTextExtractor().extract(new byte[0], "empty.txt"))
                .isInstanceOf(MaterialParseException.class)
                .hasMessageContaining("空的");
    }
}
