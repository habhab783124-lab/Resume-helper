package com.shijie.resumehelper.interview;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * T04：POST /api/interviews/{id}/answers 仅保存回答、不调用模型（计划 4.2 / 6.1）。
 * 覆盖正确保存、同 ID 同内容幂等、同 ID 不同内容 409、换 ID 覆盖 409、非当前题、
 * 5000 字边界、跨用户隔离，以及模型调用数为 0。
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.material.storage-dir=./target/test-materials",
                "app.interview.storage-dir=${java.io.tmpdir}/resume-helper-answer-api-${random.uuid}",
                "app.auth.enforce=false"
        })
@AutoConfigureMockMvc
class InterviewAnswerApiTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean AiClientResolver resolver;

    private static final String USER = "answer-user-a";

    private String startInterview(String owner, String answerBody) throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> InterviewTestFixtures.question("请介绍你的项目？"));
        var upload = mvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                "Java Redis 项目经验，负责缓存优化。".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header(UserContext.HEADER, owner))
                .andExpect(status().isOk())
                .andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/material/id").asText();
        var start = mvc.perform(post("/api/interviews").header(UserContext.HEADER, owner)
                        .contentType("application/json")
                        .content("{\"materialId\":\"" + material + "\",\"questionCount\":2}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(start.getResponse().getContentAsString()).at("/data/sessionId").asText();
    }

    private String currentQuestion(String sessionId) throws Exception {
        var res = mvc.perform(get("/api/interviews/" + sessionId).header(UserContext.HEADER, USER))
                .andExpect(status().isOk()).andReturn();
        return json.readTree(res.getResponse().getContentAsString()).at("/data/currentQuestion/questionId").asText();
    }

    @Test
    void savesAnswerWithoutModelAndDropsCurrentQuestion() throws Exception {
        // 模型若被调用会抛错，证明 saveAnswer 不调它
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);

        var saved = mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns[0].answer").value("负责缓存优化"))
                .andExpect(jsonPath("$.data.currentQuestion").doesNotExist())
                .andReturn();
        long revision = json.readTree(saved.getResponse().getContentAsString()).at("/data/revision").asLong();
        assertTrue(revision >= 1);
    }

    @Test
    void historyIsOwnerScopedAndDeletingTheSameRoundTwiceIsIdempotent() throws Exception {
        String id = startInterview(USER, null);
        String ownHistory = mvc.perform(get("/api/interviews").header(UserContext.HEADER, USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertTrue(json.readTree(ownHistory).at("/data/items").toString().contains(id));
        String otherHistory = mvc.perform(get("/api/interviews").header(UserContext.HEADER, "answer-user-b"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(otherHistory).at("/data/items").toString().contains(id));

        mvc.perform(delete("/api/interviews/" + id).header(UserContext.HEADER, USER)).andExpect(status().isOk());
        mvc.perform(delete("/api/interviews/" + id).header(UserContext.HEADER, USER)).andExpect(status().isOk());
        String afterDelete = mvc.perform(get("/api/interviews").header(UserContext.HEADER, USER))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertFalse(json.readTree(afterDelete).at("/data/items").toString().contains(id));
    }

    @Test
    void sameSubmissionIdSameContentIsIdempotent() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);

        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk());
        // 重复请求：同 ID 同内容，幂等返回，不产生第二条回答
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.turns.length()").value(1));
    }

    @Test
    void sameSubmissionIdDifferentContentIsRejected() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"负责缓存优化\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"换了一个回答\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void differentSubmissionIdCannotOverwriteAnsweredQuestion() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-1\",\"answer\":\"第一个回答\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-2\",\"answer\":\"另一个回答\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void nonCurrentQuestionIsRejected() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> InterviewTestFixtures.question("问题"));
        String id = startInterview(USER, null);
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"not-the-current-id\",\"submissionId\":\"sub-x\",\"answer\":\"回答\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void validatesAnswerLengthBoundaries() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-empty\",\"answer\":\"   \"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-long\",\"answer\":\"" + "字".repeat(5001) + "\"}"))
                .andExpect(status().isBadRequest());
        // 5000 字正好通过
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-bound\",\"answer\":\"" + "字".repeat(5000) + "\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void crossUserCannotSaveAnswer() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        String id = startInterview(USER, null);
        String q = currentQuestion(id);
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, "answer-user-b")
                .contentType("application/json")
                .content("{\"questionId\":\"" + q + "\",\"submissionId\":\"sub-x\",\"answer\":\"偷答\"}"))
                .andExpect(status().isNotFound());
    }

    /** 13 题会话的前 12 题答完后，末题是岗位专项。 */
    @Test
    void saveAnswerOnSpecialtyLastQuestionFinishesAsCompleted() throws Exception {
        String id = startThirteenQuestionInterview();
        for (int i = 0; i < 12; i++) {
            String q = currentQuestion(id);
            mvc.perform(post("/api/interviews/" + id + "/turns").header(UserContext.HEADER, USER)
                            .contentType("application/json")
                            .content("{\"questionId\":\"" + q + "\",\"answer\":\"我的回答\"}"))
                    .andExpect(status().isOk());
        }
        // 末题是专项：仅保存回答即结束，且不调用模型
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class)))
                .thenReturn((s, p) -> { throw new AssertionError("saveAnswer 不得调用模型"); });
        mvc.perform(post("/api/interviews/" + id + "/answers").header(UserContext.HEADER, USER)
                        .contentType("application/json")
                        .content("{\"questionId\":\"" + currentQuestion(id) + "\",\"submissionId\":\"sub-last\","
                                + "\"answer\":\"实现并分析复杂度\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINISHED"))
                .andExpect(jsonPath("$.data.finishReason").value("COMPLETED"))
                .andExpect(jsonPath("$.data.turns.length()").value(13))
                .andExpect(jsonPath("$.data.currentQuestion").doesNotExist());
    }

    private String startThirteenQuestionInterview() throws Exception {
        when(resolver.resolve(any(jakarta.servlet.http.HttpServletRequest.class))).thenReturn(specialtyAi());
        var upload = mvc.perform(multipart("/api/materials")
                        .file(new MockMultipartFile("file", "cv.txt", "text/plain",
                                "Java Redis 项目经验，负责缓存优化。".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                        .header(UserContext.HEADER, USER))
                .andExpect(status().isOk())
                .andReturn();
        String material = json.readTree(upload.getResponse().getContentAsString()).at("/data/material/id").asText();
        var start = mvc.perform(post("/api/interviews").header(UserContext.HEADER, USER)
                        .contentType("application/json")
                        .content("{\"materialId\":\"" + material + "\",\"questionCount\":13}"))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(start.getResponse().getContentAsString()).at("/data/sessionId").asText();
    }

    /** 与既有十二题测试同一套出题替身：专项题按选题回算法信息。 */
    private AiClient specialtyAi() {
        return (system, prompt) -> {
            if (prompt.contains("selectedAlgorithm")) {
                try {
                    return InterviewTestFixtures.json(Map.of("algorithmId",
                                    json.readTree(prompt).path("selectedAlgorithm").path("id").asText(),
                                    "description", "给定一组输入，求满足目标的结果。",
                                    "examples", "输入 [1,2]，输出 [0,1]。",
                                    "constraints", "输入长度 1 至 10000，考虑空值及边界。"));
                } catch (Exception e) {
                    throw new AssertionError(e);
                }
            }
            return InterviewTestFixtures.question("请说明你的实现和取舍？");
        };
    }

    private static void assertTrue(boolean cond) {
        org.junit.jupiter.api.Assertions.assertTrue(cond);
    }
}
