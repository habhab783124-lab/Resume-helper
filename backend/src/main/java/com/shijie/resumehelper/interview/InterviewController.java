package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.job.ai.AiClientResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api")
public class InterviewController {
    private final MaterialService materials;
    private final InterviewService interviews;
    private final InterviewAgent agent;
    private final AiClientResolver clients;
    public InterviewController(MaterialService materials, InterviewService interviews, InterviewAgent agent, AiClientResolver clients) {
        this.materials = materials; this.interviews = interviews; this.agent = agent; this.clients = clients;
    }
    public record StartRequest(String materialId, String targetRole, Integer questionCount) {}
    public record AnswerRequest(String questionId, String answer) {}
    private String owner(HttpServletRequest request) { return request.getSession(true).getId(); }

    @PostMapping("/ai/check")
    public ApiResponse<InterviewAgent.AiCheck> checkAi(HttpServletRequest request) {
        return ApiResponse.ok(agent.checkConnection(clients.resolve(request)));
    }

    @PostMapping(value = "/materials", consumes = "multipart/form-data")
    public ApiResponse<MaterialService.Material> upload(@RequestParam("file") MultipartFile file, HttpServletRequest request) {
        return ApiResponse.ok(materials.upload(owner(request), file));
    }
    @GetMapping("/materials/{id}")
    public ApiResponse<MaterialService.Material> material(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(materials.get(owner(request), id));
    }
    @PostMapping("/interviews")
    public ApiResponse<InterviewService.Session> start(@RequestBody StartRequest body, HttpServletRequest request) {
        if (body.materialId() == null || body.materialId().isBlank()) throw new IllegalArgumentException("请先上传简历");
        return ApiResponse.ok(interviews.start(owner(request), body.materialId(), body.targetRole(),
                body.questionCount() == null ? 5 : body.questionCount(), clients.resolve(request)));
    }
    @GetMapping("/interviews/{id}")
    public ApiResponse<InterviewService.Session> get(@PathVariable String id, HttpServletRequest request) {
        return ApiResponse.ok(interviews.get(owner(request), id));
    }
    @PostMapping("/interviews/{id}/turns")
    public ApiResponse<InterviewService.Session> answer(@PathVariable String id, @RequestBody AnswerRequest body, HttpServletRequest request) {
        return ApiResponse.ok(interviews.answer(owner(request), id, body.questionId(), body.answer(), clients.resolve(request)));
    }
    @PostMapping("/interviews/{id}/finish")
    public ApiResponse<InterviewService.Session> finish(@PathVariable String id HttpServletRequest request) {
        return ApiResponse.ok(interviews.finish(owner(request) id));
    }

    @GetMapping("/interviews/{id}/report")
    public ApiResponse<InterviewAgent.InterviewReport> report(@PathVariable String id HttpServletRequest request) {
        return ApiResponse.ok(interviews.report(owner(request) id clients.resolve(request)));
    }
}