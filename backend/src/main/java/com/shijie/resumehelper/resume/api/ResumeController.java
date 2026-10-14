package com.shijie.resumehelper.resume.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.service.MaterialService;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.DraftView;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.SaveVersionRequest;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.SuggestRequest;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.SuggestView;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.VersionDetailView;
import com.shijie.resumehelper.resume.api.dto.ResumeViews.VersionView;
import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import com.shijie.resumehelper.resume.service.ResumeService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 简历生成接口（功能四）。对应产品方案 8 的草案与 3.B 的要求。
 *
 * <h2>取材自哪里</h2>
 * 简历内容来自用户在「材料整理」里<b>确认过的求职画像</b>（{@code /api/profile}）。
 * 所以这里依赖 {@link MaterialService} 读画像，和面试模块读简历原文是同一套做法——
 * 画像是跨模块的共享数据，来源只有一处，不要各模块自己再存一份。
 *
 * <h2>建议为什么不直接改简历</h2>
 * 产品原型上写着「AI 建议不会自动覆盖原文」。所以 {@code /suggest} 只<b>返回建议</b>，
 * 由用户在前端逐条采纳；采纳后的内容再由 {@code /versions} 保存。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeController {

    private final ResumeService service;
    private final MaterialService materials;
    private final AiClientResolver aiResolver;

    public ResumeController(ResumeService service, MaterialService materials, AiClientResolver aiResolver) {
        this.service = service;
        this.materials = materials;
        this.aiResolver = aiResolver;
    }

    /** 按当前画像生成一份简历内容。 */
    @GetMapping
    public ApiResponse<DraftView> draft(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId) {

        String owner = UserContext.resolve(userId);
        UserProfileSnapshot profile = materials.findProfile(owner).orElse(null);
        ResumeDraft draft = service.buildDraft(profile);

        if (draft.isEmpty()) {
            return ApiResponse.ok(new DraftView(draft, true,
                    "还没有可用的资料。先去「材料整理」上传一份简历或粘贴一段经历并确认，"
                            + "简历工作台会直接用它生成内容。"));
        }
        String message = profile == null ? "这份内容来自本机演示资料（还没有保存过画像）。" : "";
        return ApiResponse.ok(new DraftView(draft, false, message));
    }

    /**
     * 针对目标职位 / 岗位描述给出逐条改写建议。
     *
     * <p>没有 AI token 时返回空建议 + 说明，而不是编几条出来——
     * 简历是要投出去的，假建议比没建议危险得多。
     */
    @PostMapping("/suggest")
    public ApiResponse<SuggestView> suggest(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @RequestBody(required = false) SuggestRequest body,
            HttpServletRequest request) {

        String owner = UserContext.resolve(userId);
        // 允许前端把改过但还没保存的简历发过来，这样建议针对的是"屏幕上这一份"
        ResumeDraft draft = body != null && body.draft() != null
                ? body.draft()
                : service.buildDraft(materials.findProfile(owner).orElse(null));

        AiClient aiClient = aiResolver.resolve(request);
        ResumeService.SuggestResult result = service.suggest(
                draft,
                body == null ? "" : body.targetTitle(),
                body == null ? "" : body.jd(),
                aiClient);
        return ApiResponse.ok(new SuggestView(result.suggestions(), result.message()));
    }

    /** 已保存的版本列表。 */
    @GetMapping("/versions")
    public ApiResponse<List<VersionView>> versions(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId) {

        List<VersionView> views = service.listVersions(UserContext.resolve(userId)).stream()
                .map(VersionView::from)
                .toList();
        return ApiResponse.ok(views);
    }

    /** 载入某一版，接着改。 */
    @GetMapping("/versions/{id}")
    public ResponseEntity<ApiResponse<VersionDetailView>> version(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @PathVariable("id") String versionId) {

        return service.findVersion(UserContext.resolve(userId), versionId)
                .map(version -> ResponseEntity.ok(ApiResponse.ok(
                        new VersionDetailView(VersionView.from(version), version.draft()))))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.fail("找不到这个版本，可能已经被删除了。")));
    }

    /** 保存为新版本。 */
    @PostMapping("/versions")
    public ApiResponse<VersionView> saveVersion(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @RequestBody SaveVersionRequest body) {

        ResumeVersion saved = service.saveVersion(
                UserContext.resolve(userId),
                body == null ? null : body.name(),
                body == null ? null : body.targetTitle(),
                body == null ? null : body.jd(),
                body == null ? null : body.draft());
        return ApiResponse.ok(VersionView.from(saved), "已保存为新版本");
    }

    @DeleteMapping("/versions/{id}")
    public ResponseEntity<ApiResponse<String>> deleteVersion(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @PathVariable("id") String versionId) {

        boolean deleted = service.deleteVersion(UserContext.resolve(userId), versionId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("找不到这个版本，可能已经被删除了。"));
        }
        return ResponseEntity.ok(ApiResponse.ok("已删除", "版本已删除"));
    }
}
