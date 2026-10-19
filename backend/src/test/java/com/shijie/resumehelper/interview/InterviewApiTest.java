package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 面试接口的端到端测试（MockMvc）。
 *
 * <h2>统一后的两处改动</h2>
 * <ol>
 *   <li><b>身份从 HTTP session 改成 {@code X-User-Id} 请求头</b>。
 *       原来面试模块用 {@code request.getSession().getId()}，材料模块用请求头——
 *       两边对不上号，面试按 session id 去查材料<b>永远查不到</b>，
 *       而且不报错，只显示"简历不存在"，非常难排查。
 *       现在统一走 {@code UserContext}；这里改成传请求头，
 *       「跨用户看不到别人的数据」这条断言依然成立。</li>
 *   <li><b>上传接口换成了材料模块的</b>（路径 {@code /api/materials} 没变），
 *       所以取 id 的 JSON 路径从 {@code $.data.materialId} 变成 {@code $.data.material.id}。</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        // 测试不要往仓库的 data/ 里写用户简历，丢到 target 下
        properties = {"app.material.storage-dir=./target/test-materials",
                "app.interview.storage-dir=./target/test-api-interviews",
                "app.auth.enforce=false"})
@AutoConfigureMockMvc
class InterviewApiTest {

    @org.springframework.boot.test.web.server.LocalServerPort int port;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientResolver resolver;

    private static final String USER_A = "test-user-a";
    private static final String USER_B = "test-user-b";

    @Test void uploadStartAnswerRestoreFinishAndIsolation() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s,p)->InterviewTestFixtures.question("请介绍你的项目？"));

        var upload = mvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                "Java Redis 项目经验，负责缓存优化。".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header(UserContext.HEADER, USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.material.status").value("TEXT_EXTRACTED"))
                .andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/material/id").asText();

        // 别的用户看不到这份材料
        mvc.perform(get("/api/materials/" + material).header(UserContext.HEADER, USER_B))
                .andExpect(status().isNotFound());

        // 别的用户也不能拿它开面试
        mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER_B)
                        .contentType("application/json")
                        .content("{\"materialId\":\"" + material + "\",\"questionCount\":2}"))
                .andExpect(status().isNotFound());

        var start = mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER_A)
                        .contentType("application/json")
                        .content("{\"materialId\":\"" + material + "\",\"questionCount\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.questionCount").value(13))
                .andReturn();
        var data = json.readTree(start.getResponse().getContentAsString()).path("data");
        mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER_A)
                        .contentType("application/json").content("{\"materialId\":\"" + material + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.questionCount").value(13));
        String id = data.path("sessionId").asText();
        String q = data.at("/currentQuestion/questionId").asText();

        mvc.perform(post("/api/interviews/" + id + "/turns").header(UserContext.HEADER, USER_A)
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + q + "\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(2));

        mvc.perform(get("/api/interviews/" + id).header(UserContext.HEADER, USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns[0].answer").value("负责缓存优化"));

        // 换一个用户，会话就看不见了
        mvc.perform(get("/api/interviews/" + id).header(UserContext.HEADER, USER_B))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/interviews/" + id + "/finish").header(UserContext.HEADER, USER_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINISHED"));
    }

    @Test void startingWithUnknownMaterialGivesReadableNotFound() throws Exception {
        mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER_A)
                        .contentType("application/json")
                        .content("{\"materialId\":\"mat-does-not-exist\",\"questionCount\":3}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("找不到这份简历")));
    }

    @Test void malformedRequestsHaveReadableErrors() throws Exception {
        mvc.perform(post("/api/interviews").contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        mvc.perform(multipart("/api/materials").header(UserContext.HEADER, USER_A))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
        // 缺少 materialId
        mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER_A)
                        .contentType("application/json").content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.success").value(false));
    }

    @Test void realHttpRejectsOversizedUploadWithReadableResponse() throws Exception {
        // 超过 spring.servlet.multipart.max-file-size（10MB）时，异常在进入控制器之前
        // 就抛出来了，必须由全局处理器翻译成人话——否则用户只看到「服务端开小差了」。
        String body = "--boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"cv.txt\"\r\nContent-Type: text/plain\r\n\r\n"
                + "a".repeat(11 * 1024 * 1024) + "\r\n--boundary--\r\n";
        var request = java.net.http.HttpRequest.newBuilder(java.net.URI.create("http://localhost:" + port + "/api/materials"))
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
