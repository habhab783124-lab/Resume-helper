package com.shijie.resumehelper.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.auth.api.AuthController;
import com.shijie.resumehelper.auth.config.AuthConfiguration;
import com.shijie.resumehelper.auth.config.AuthProperties;
import com.shijie.resumehelper.auth.store.FileSystemUserStore;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.interview.UploadErrorHandler;
import com.shijie.resumehelper.job.config.WebMvcConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;

/**
 * 真实注册 / 登录接口的端到端测试。
 *
 * <p>这里刻意使用默认的 {@code app.auth.enforce=true}：
 * 没带令牌的业务请求应该 401，令牌里的身份应该覆盖客户端伪造的 X-User-Id。
 */
@WebMvcTest(controllers = AuthController.class, excludeFilters =
        @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = WebMvcConfiguration.class))
@Import({AuthConfiguration.class, AuthApiTest.EchoController.class})
class AuthApiTest {

    private static final Path AUTH_DIR = Path.of(
            System.getProperty("java.io.tmpdir"),
            "resume-helper-auth-" + UUID.randomUUID());

    @DynamicPropertySource
    static void authProperties(DynamicPropertyRegistry registry) {
        registry.add("app.auth.storage-dir", AUTH_DIR::toString);
        registry.add("app.auth.token-secret", () -> "test-secret-that-is-long-enough-for-hmac");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @MockBean UploadErrorHandler uploadErrorHandler;

    @Test
    void registerLoginReadMeAndRejectBadCredentials() throws Exception {
        String email = uniqueEmail();
        String password = "TestPass123!";

        register(email, password, "林同学")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.user.email").value(email))
                .andExpect(jsonPath("$.data.user.displayName").value("林同学"));

        // 重复邮箱不能再注册
        register(email, password, "另一个人")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.success").value(false));

        // 密码错误要 401，且不能泄露“邮箱是否存在”
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("邮箱或密码不正确。"));

        JsonNode loggedIn = json.readTree(mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("email", email, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String token = loggedIn.at("/data/token").asText();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(email));
    }

    @Test
    void tokenIdentityOverridesSpoofedHeaderAndRejectsTampering() throws Exception {
        String aliceEmail = uniqueEmail();
        String bobEmail = uniqueEmail();
        String password = "TestPass123!";

        String aliceToken = registerAndReadToken(aliceEmail, password, "Alice");
        String bobToken = registerAndReadToken(bobEmail, password, "Bob");
        String bobBody = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String bobId = json.readTree(bobBody).at("/data/id").asText();
        String aliceBody = mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        String aliceId = json.readTree(aliceBody).at("/data/id").asText();

        // 带着 Alice 的令牌，却伪造 Bob 的 X-User-Id：最终身份必须仍是 Alice
        mvc.perform(get("/api/auth/me")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("X-User-Id", bobId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value(aliceEmail));

        // 业务控制器仍然读 X-User-Id，但过滤器会把它替换成令牌里的身份
        mvc.perform(get("/api/test/whoami")
                        .header("Authorization", "Bearer " + aliceToken)
                        .header("X-User-Id", bobId))
                .andExpect(status().isOk())
                .andExpect(content().string(aliceId));

        String tampered = aliceToken.substring(0, aliceToken.length() - 2) + "aa";
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protectedApiRequiresLoginAndPasswordsAreStoredHashed() throws Exception {
        mvc.perform(get("/api/profile"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));

        String email = uniqueEmail();
        String password = "PlainTextMustNotAppear!";
        registerAndReadToken(email, password, "哈希检查");

        String stored = Files.readString(AUTH_DIR.resolve("users.json"), StandardCharsets.UTF_8);
        assertFalse(stored.contains(password), "账号文件不能出现明文密码");
        assertTrue(stored.contains("pbkdf2-sha256$"), "密码应保存为 PBKDF2 哈希");

        // 模拟服务重启：新实例从同一个文件读回账号
        AuthProperties properties = new AuthProperties();
        properties.setStorageDir(AUTH_DIR.toString());
        FileSystemUserStore reopened = new FileSystemUserStore(properties, new ObjectMapper());
        assertTrue(reopened.findByEmail(email).isPresent(), "账号应能从 users.json 重新读回");
    }

    private org.springframework.test.web.servlet.ResultActions register(String email,
                                                                        String password,
                                                                        String displayName) throws Exception {
        return mvc.perform(post("/api/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "email", email,
                        "password", password,
                        "displayName", displayName))));
    }

    private String registerAndReadToken(String email, String password, String displayName) throws Exception {
        String body = mvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "email", email,
                                "password", password,
                                "displayName", displayName))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(body).at("/data/token").asText();
    }

    private static String uniqueEmail() {
        return "user-" + UUID.randomUUID() + "@example.com";
    }

    /** 模拟材料/简历/面试控制器：它们通过 @RequestHeader 读 X-User-Id。 */
    @RestController
    static class EchoController {
        @GetMapping("/api/test/whoami")
        String whoami(@RequestHeader(value = UserContext.HEADER, required = false) String userId) {
            return userId == null ? "" : userId;
        }
    }
}
