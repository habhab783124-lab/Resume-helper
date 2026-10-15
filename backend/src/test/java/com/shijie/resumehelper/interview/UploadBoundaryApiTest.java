package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Real HTTP is required here: MockMvc multipart bypasses servlet upload parsing limits. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.servlet.multipart.max-file-size=10MB",
        "spring.servlet.multipart.max-request-size=12MB",
        "app.material.max-file-size-mb=10",
        "app.material.storage-dir=./target/test-upload-boundary-materials",
        "app.resume.storage-dir=./target/test-upload-boundary-resumes",
        "app.interview.speech.max-audio-bytes=5242880",
        "server.tomcat.max-swallow-size=20MB"
})
class UploadBoundaryApiTest {
    private static final int MIB = 1024 * 1024;
    private static final String RESUME = "Java engineer with Redis caching and Spring Boot project experience. Reduced latency by 75 percent.";
    private static final String GLOBAL_LIMIT_MESSAGE = "上传内容超过大小限制（单文件上限 10MB，请求总量上限 12MB）";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired InterviewService interviews;
    @Autowired MaterialService materials;
    @MockBean AiClientResolver resolver;
    // Only the remote provider is replaced. AudioNormalizer and all session validation remain real.
    @MockBean SpeechClient speech;
    private String owner;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach void setUp() {
        owner = "upload-boundary-" + UUID.randomUUID();
        when(resolver.resolve(any(HttpServletRequest.class))).thenReturn(null);
    }

    @Test void sixMiBValidMaterialPassesGlobalLimitAndIsParsedAndStoredForOwner() throws Exception {
        byte[] pdf = paddedResumePdf();
        assertTrue(pdf.length > 6 * MIB && pdf.length < 7 * MIB);
        var response = upload("/api/materials", Map.of(), List.of(new FilePart("cv.pdf", "application/pdf", pdf)));
        assertEquals(200, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertTrue(body.path("success").asBoolean());
        assertEquals("TEXT_EXTRACTED", body.at("/data/material/status").asText());
        String materialId = body.at("/data/material/id").asText();
        assertFalse(materialId.isBlank());
        assertTrue(materials.originalText(owner, materialId).orElseThrow().contains(RESUME));
        assertTrue(materials.find("other-" + owner, materialId).isEmpty());
        assertEquals(pdf.length, body.at("/data/material/sizeBytes").asInt());
        int textLength = body.at("/data/material/textLength").asInt();
        assertTrue(textLength > 50 && textLength < 50000);
    }

    @Test void sixMiBAudioReachesRealNormalizerAndGetsSpeechSpecific413() throws Exception {
        var session = interviews.start(owner, "test-material", RESUME, "Java engineer", 2,
                (system, prompt) -> InterviewTestFixtures.question("Please describe your caching project?"));
        var response = upload("/api/interviews/" + session.sessionId() + "/transcriptions",
                Map.of("questionId", session.currentQuestion().questionId(), "requestId", UUID.randomUUID().toString()),
                List.of(new FilePart("recording.webm", "audio/webm", new byte[6 * MIB])));
        assertError(response, 413, "录音文件超过大小限制，请缩短后重新录音");
        assertEquals(session, interviews.get(owner, session.sessionId()));
        verifyNoInteractions(speech);
    }

    @Test void elevenMiBFileHitsGlobalFileLimitWithConfiguredMessage() throws Exception {
        var response = upload("/api/materials", Map.of(),
                List.of(new FilePart("cv.txt", "text/plain", new byte[11 * MIB])));
        assertError(response, 413, GLOBAL_LIMIT_MESSAGE);
        assertTrue(materials.list(owner).isEmpty());
    }

    @Test void thirteenMiBRequestHitsGlobalTotalLimitWithEachFileUnderTenMiB() throws Exception {
        var response = upload("/api/materials", Map.of(), List.of(
                new FilePart("first.txt", "text/plain", new byte[7 * MIB]),
                new FilePart("second.txt", "text/plain", new byte[6 * MIB])));
        assertError(response, 413, GLOBAL_LIMIT_MESSAGE);
        assertTrue(materials.list(owner).isEmpty());
    }

    private void assertError(HttpResponse<String> response, int status, String message) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        JsonNode body = json.readTree(response.body());
        assertFalse(body.path("success").asBoolean());
        assertEquals(message, body.path("message").asText());
    }

    private record FilePart(String filename, String contentType, byte[] bytes) {}

    private HttpResponse<String> upload(String path, Map<String, String> fields, List<FilePart> files) throws Exception {
        String boundary = "boundary-" + UUID.randomUUID();
        var body = new ByteArrayOutputStream();
        for (var field : fields.entrySet()) {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field.getKey()
                    + "\"\r\n\r\n" + field.getValue() + "\r\n");
        }
        for (FilePart file : files) {
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
                    + file.filename() + "\"\r\nContent-Type: " + file.contentType() + "\r\n\r\n");
            body.write(file.bytes());
            write(body, "\r\n");
        }
        write(body, "--" + boundary + "--\r\n");
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20)).header(UserContext.HEADER, owner)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static void write(ByteArrayOutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] paddedResumePdf() throws Exception {
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 10);
                content.newLineAtOffset(20, 700);
                content.showText(RESUME);
                content.endText();
            }
            // An uncompressed catalog stream increases bytes without exceeding the visible-text bound.
            var padding = new PDStream(document);
            try (var stream = padding.createOutputStream()) { stream.write(new byte[6 * MIB]); }
            document.getDocumentCatalog().getCOSObject().setItem(COSName.getPDFName("TestPadding"), padding);
            document.save(output);
            return output.toByteArray();
        }
    }
}
