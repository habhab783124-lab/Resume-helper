package com.shijie.resumehelper.interview;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.zip.ZipInputStream;

@Service
public class MaterialService {
    static final int MAX_BYTES = 5 * 1024 * 1024;
    static final int MAX_TEXT = 20000;
    private final Map<String, Stored> materials = new HashMap<>();
    public record Material(String materialId, String fileName, String status, String text) {}
    private record Stored(String owner, Material material, Instant expires) {}

    public Material upload(String owner, MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的简历文件");
        if (file.getSize() > MAX_BYTES) throw new IllegalArgumentException("简历文件不能超过 5MB");
        String name = Objects.requireNonNullElse(file.getOriginalFilename(), "resume.txt").replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        if (name.length() > 200) name = name.substring(name.length() - 200);
        String suffix = name.toLowerCase(Locale.ROOT);
        String text;
        try (var input = file.getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("简历文件不能超过 5MB");
            if (suffix.endsWith(".pdf")) {
                try (var pdf = Loader.loadPDF(bytes)) {
                    if (pdf.getNumberOfPages() > 30) throw new IllegalArgumentException("简历 PDF 不能超过 30 页");
                    if (!pdf.getCurrentAccessPermission().canExtractContent()) throw new IllegalArgumentException("该 PDF 不允许提取文本，请上传可读取版本");
                    text = new PDFTextStripper().getText(pdf);
                }
            } else if (suffix.endsWith(".docx")) {
                checkZip(bytes);
                try (var doc = new XWPFDocument(new ByteArrayInputStream(bytes)); var extractor = new XWPFWordExtractor(doc)) {
                    text = extractor.getText();
                }
            } else if (suffix.endsWith(".txt")) {
                text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            } else throw new IllegalArgumentException("仅支持 PDF、DOCX 或 UTF-8 TXT 简历");
        } catch (IllegalArgumentException e) { throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("无法解析文件，请检查格式或密码保护；也可以粘贴简历文本");
        }
        text = text.replace("\u0000", "").replace("\uFEFF", "").strip();
        if (text.isBlank()) throw new IllegalArgumentException("未提取到文字，扫描版简历请先转为文字或直接粘贴文本");
        if (text.length() > MAX_TEXT) throw new IllegalArgumentException("简历文本不能超过 20000 字，请精简后重新上传");
        Material result = new Material(UUID.randomUUID().toString(), name, "READY", text);
        synchronized (materials) {
            materials.values().removeIf(v -> v.expires().isBefore(Instant.now()));
            if (materials.size() >= 2000 || materials.values().stream().filter(v -> v.owner().equals(owner)).count() >= 20)
                throw new InterviewException(429, "暂存简历数量已达上限，请稍后再试");
            materials.put(result.materialId(), new Stored(owner, result, Instant.now().plusSeconds(86400)));
        }
        return result;
    }

    public Material get(String owner, String id) {
        synchronized (materials) {
            Stored stored = materials.get(id);
            if (stored == null || !stored.owner().equals(owner) || stored.expires().isBefore(Instant.now()))
                throw new InterviewException(404, "简历不存在或已过期，请重新上传");
            return stored.material();
        }
    }

    private void checkZip(byte[] bytes) throws IOException {
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            int entries = 0, total = 0, n; byte[] chunk = new byte[8192];
            while (zip.getNextEntry() != null) {
                if (++entries > 200) throw new IllegalArgumentException("DOCX 内容过于复杂，请粘贴简历文本");
                while ((n = zip.read(chunk)) != -1) {
                    total += n;
                    if (total > 10 * 1024 * 1024) throw new IllegalArgumentException("DOCX 解压内容过大，请精简文件");
                }
            }
        }
    }
}
