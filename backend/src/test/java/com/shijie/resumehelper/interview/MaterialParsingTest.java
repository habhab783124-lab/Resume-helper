package com.shijie.resumehelper.interview;

import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.io.ByteArrayOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class MaterialParsingTest {
    private final MaterialService service = new MaterialService();
    @Test void readsRealPdf() throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            var page = new PDPage(); doc.addPage(page);
            try (var content = new PDPageContentStream(doc, page)) {
                content.beginText(); content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(30, 700); content.showText("Java developer Redis project"); content.endText();
            }
            doc.save(out);
            assertTrue(service.upload("a", new MockMultipartFile("file", "resume.pdf", "application/pdf", out.toByteArray())).text().contains("Redis"));
        }
    }
    @Test void readsDocxParagraphsAndTables() throws Exception {
        try (var doc = new XWPFDocument(); var out = new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("张同学 Java 开发");
            doc.createTable(1,1).getRow(0).getCell(0).setText("Redis 校园项目");
            doc.write(out);
            String text = service.upload("a", new MockMultipartFile("file", "resume.docx", "application/octet-stream", out.toByteArray())).text();
            assertTrue(text.contains("张同学")); assertTrue(text.contains("Redis"));
        }
    }
    @Test void rejectsImageOnlyAndInvalidPdf() throws Exception {
        try (var doc = new PDDocument(); var out = new ByteArrayOutputStream()) {
            doc.addPage(new PDPage()); doc.save(out);
            assertThrows(IllegalArgumentException.class, () -> service.upload("a", new MockMultipartFile("file", "scan.pdf", "application/pdf", out.toByteArray())));
        }
        assertThrows(IllegalArgumentException.class, () -> service.upload("a", new MockMultipartFile("file", "bad.pdf", "application/pdf", "broken".getBytes())));
    }
}
