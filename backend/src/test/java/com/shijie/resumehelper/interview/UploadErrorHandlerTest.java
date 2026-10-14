package com.shijie.resumehelper.interview;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.web.servlet.MultipartProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = UploadErrorHandlerTest.UploadProbe.class, properties = {
        "spring.servlet.multipart.max-file-size=2MB",
        "spring.servlet.multipart.max-request-size=3MB"
})
@ContextConfiguration(classes = {UploadErrorHandler.class, UploadErrorHandlerTest.UploadProbe.class})
@EnableConfigurationProperties(MultipartProperties.class)
class UploadErrorHandlerTest {
    @Autowired MockMvc mvc;

    @Test void oversizeMessageUsesConfiguredFileAndRequestLimits() throws Exception {
        mvc.perform(get("/upload-limit-probe"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("上传内容超过大小限制（单文件上限 2MB，请求总量上限 3MB）"));
    }

    @RestController
    static class UploadProbe {
        @GetMapping("/upload-limit-probe")
        void oversize() { throw new MaxUploadSizeExceededException(-1); }
    }
}
