package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音接口的 HTTP 层。归属与并发限制都在 {@link InterviewSpeechService}，
 * 这里只负责路由、session 归属和响应的媒体类型。
 *
 * <p>朗读成功时返回二进制音频而不是 {@link ApiResponse} 包装，省得前端再做一次 Base64；
 * 失败仍然走 {@link InterviewErrorHandler} 的统一 JSON 格式。前端因此要把
 * 「成功是 audio、失败是 JSON」分开解析。
 */
@RestController
@RequestMapping("/api")
public class InterviewSpeechController {
    private final InterviewSpeechService speechService;

    public InterviewSpeechController(InterviewSpeechService speechService) {
        this.speechService = speechService;
    }

    private String owner(HttpServletRequest request) {
        return request.getSession(true).getId();
    }

    @GetMapping("/interview-speech/capabilities")
    public ApiResponse<InterviewSpeechService.Capabilities> capabilities() {
        return ApiResponse.ok(speechService.capabilities());
    }

    @PostMapping("/interviews/{id}/questions/{questionId}/speech")
    public ResponseEntity<byte[]> speech(@PathVariable String id, @PathVariable String questionId,
                                         HttpServletRequest request) {
        SpeechClient.SpeechAudio audio = speechService.synthesize(owner(request), id, questionId);
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .contentType(MediaType.parseMediaType(audio.contentType()))
                .body(audio.bytes());
    }

    @PostMapping(value = "/interviews/{id}/transcriptions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<InterviewSpeechService.Transcription> transcribe(
            @PathVariable String id,
            @RequestParam String questionId,
            @RequestParam String requestId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        return ApiResponse.ok(speechService.transcribe(owner(request), id, questionId, requestId, file));
    }
}
