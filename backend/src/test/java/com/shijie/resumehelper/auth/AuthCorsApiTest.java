package com.shijie.resumehelper.auth;

import com.shijie.resumehelper.auth.api.AuthController;
import com.shijie.resumehelper.auth.config.AuthConfiguration;
import com.shijie.resumehelper.interview.UploadErrorHandler;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.config.WebMvcConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = AuthController.class, properties = "app.auth.token-secret=cors-test-signing-secret")
@Import({AuthConfiguration.class, WebMvcConfiguration.class, JobModuleProperties.class})
class AuthCorsApiTest {
    @Autowired MockMvc mvc;
    @MockBean UploadErrorHandler uploadErrorHandler;

    @Test void expiredLoginRemainsReadableByAllowedFrontend() throws Exception {
        mvc.perform(get("/api/auth/me").header("Origin", "http://localhost:5173")
                .header("Authorization", "Bearer expired-token"))
            .andExpect(status().isUnauthorized())
            .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
            .andExpect(jsonPath("$.message").value("登录已过期，请重新登录。"));
    }

    @Test void untrustedOriginsCannotReadAuthenticationErrors() throws Exception {
        mvc.perform(get("/api/auth/me").header("Origin", "https://untrusted.example"))
            .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }
}
