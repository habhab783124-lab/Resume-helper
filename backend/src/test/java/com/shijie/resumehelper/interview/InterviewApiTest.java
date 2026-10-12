package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.job.ai.AiClient;
import com.shijie.resumehelper.job.ai.AiClientResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT) @AutoConfigureMockMvc
class InterviewApiTest {
    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientResolver resolver;
    @Test void uploadStartAnswerRestoreFinishAndIsolation() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s,p)->"请介绍你的项目？");
        var owner = new MockHttpSession();
        var upload = mvc.perform(multipart("/api/materials").file(new MockMultipartFile("file", "cv.txt", "text/plain", "Java Redis 项目经验".getBytes(java.nio.charset.StandardCharsets.UTF_8))).session(owner))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("READY")).andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/materialId").asText();
        mvc.perform(get("/api/materials/"+material).session(new MockHttpSession())).andExpect(status().isNotFound());
        var start = mvc.perform(post("/api/interviews").session(owner).contentType("application/json").content("{\"materialId\":\""+material+"\",\"questionCount\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("ACTIVE")).andReturn();
        var data = json.readTree(start.getResponse().getContentAsString()).path("data");
        String id = data.path("sessionId").asText();
        String q = data.at("/currentQuestion/questionId").asText();
        mvc.perform(post("/api/interviews/"+id+"/turns").session(owner).contentType("application/json").content("{\"questionId\":\""+q+"\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.turns.length()").value(2));
        mvc.perform(get("/api/interviews/"+id).session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.turns[0].answer").value("负责缓存优化"));
        mvc.perform(get("/api/interviews/"+id).session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(post("/api/interviews/"+id+"/finish").session(owner)).andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("FINISHED"));
    }
    @Test void malformedRequestsHaveReadableErrors() throws Exception {
        mvc.perform(post("/api/interviews").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(multipart("/api/materials")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }
    @Test void realHttpRejectsOversizedUploadWithReadableResponse() throws Exception {
        String body = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cv.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + "a".repeat(5 * 1024 * 1024 + 1) + "\r\n--boundary--\r\n";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:"+port+"/api/materials"))
                .header("Content-Type", "multipart/form-data; boundary=boundary")
                .POST(java.net.http.HttpRequest.BodyPublishers.ofString(body)).build();
        var response = java.net.http.HttpClient.newHttpClient().send(request, java.net.http.HttpResponse.BodyHandlers.ofString());
        org.junit.jupiter.api.Assertions.assertEquals(413, response.statusCode());
        org.junit.jupiter.api.Assertions.assertFalse(json.readTree(response.body()).path("success").asBoolean());
    }
    @Test void checksModelConnectionAndTranslatesFailures() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(null);
        mvc.perform(post("/api/ai/check")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.success").value(false));
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> "正常");
        mvc.perform(post("/api/ai/check")).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.model").value("ai"));
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class)))
                .thenReturn((s, p) -> { throw new AiClient.AiException("API Key 无效（HTTP 401）", false); });
        mvc.perform(post("/api/ai/check")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("API Key 无效（HTTP 401）"));
    }
}
