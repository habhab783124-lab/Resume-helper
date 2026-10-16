package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 语音接口的 HTTP 层。归属与并发限制都在 {@link InterviewSpeechService}，
 * 这里只负责路由、身份解析和响应的媒体类型。
 *
 * <p>朗读成功时返回二进制音频而不是 {@link ApiResponse} 包装，省得前端再做一次 Base64；
 * 失败仍然走 {@link InterviewErrorHandler} 的统一 JSON 格式。前端因此要把
 * 「成功是 audio、失败是 JSON」分开解析。
 *
 * <h2>身份来源必须是 {@link UserContext}</h2>
 * 这里原来用的是 {@code request.getSession(true).getId()}，而 {@link InterviewController}
 * 用的是请求头。两边对不上号的后果是：面试在 A 身份下创建，语音却在 B 身份下查找，
 * <b>永远 404「面试不存在」</b>——而且不报错、不提示身份问题，只显示"找不到"，
 * 排查时很容易怀疑到会话过期上去。
 *
 * <p>身份是跨模块的约定，只能有一个来源。这个 bug 和"材料模块用请求头、面试模块用
 * session id"是同一类问题，在统一时一并修掉。
 */
@RestController
@RequestMapping("/api")
public class InterviewSpeechController {
    private final InterviewSpeechService speechService;
    private final SpeechClientResolver speechClients;

    public InterviewSpeechController(InterviewSpeechService speechService, SpeechClientResolver speechClients) {
        this.speechService = speechService;
        this.speechClients = speechClients;
    }

    private String owner(HttpServletRequest request) {
        return UserContext.resolve(request);
    }

    @GetMapping("/interview-speech/capabilities")
    public ApiResponse<InterviewSpeechService.Capabilities> capabilities(HttpServletRequest request) {
        // 能力判断必须跟着本次请求实际使用的 key 走，否则「设置页说可用、面试页说不可用」。
        return ApiResponse.ok(speechService.capabilities(speechClients.apiKey(request)));
    }

    @PostMapping("/interview-speech/check")
    public ApiResponse<InterviewSpeechService.SpeechCheck> check(HttpServletRequest request) {
        return ApiResponse.ok(speechService.check(owner(request), speechClients.resolve(request)));
    }

    @PostMapping("/interviews/{id}/questions/{questionId}/speech")
    public ResponseEntity<byte[]> speech(@PathVariable String id, @PathVariable String questionId,
                                         HttpServletRequest request) {
        SpeechClient.SpeechAudio audio = speechService.synthesize(owner(request), id, questionId,
                speechClients.resolve(request));
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
        return ApiResponse.ok(speechService.transcribe(owner(request), id, questionId, requestId, file,
                speechClients.resolve(request)));
    }
}
