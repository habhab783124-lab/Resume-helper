package com.shijie.resumehelper.material.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.ai.AiClientResolver;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialDetailView;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialView;
import com.shijie.resumehelper.material.api.dto.MaterialViews.PasteTextRequest;
import com.shijie.resumehelper.material.service.MaterialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * 材料整理接口。对应产品方案 8 的接口草案：
 * {@code POST /api/materials}、{@code GET /api/materials/{id}}。
 *
 * <p>在此之上补了三个产品方案 3.A 明确要求、但草案里没列出的接口：
 * <ul>
 *   <li>{@code GET /api/materials} —— 列出已整理的材料（原型页面有"已整理 N 份"）</li>
 *   <li>{@code DELETE /api/materials/{id}} —— "用户可以删除材料"</li>
 *   <li>{@code POST /api/materials/text} —— 粘贴文本，扫描件的兜底路径</li>
 * </ul>
 *
 * <p>身份来自 {@code X-User-Id} 请求头，缺省 {@code demo-user}。
 * 现在是演示阶段的约定，接入真实认证后只需改 {@link UserContext}。
 */
@RestController
@RequestMapping("/api/materials")
public class MaterialController {

    private final MaterialService service;
    private final AiClientResolver aiResolver;

    public MaterialController(MaterialService service, AiClientResolver aiResolver) {
        this.service = service;
        this.aiResolver = aiResolver;
    }

    /** 已整理的材料列表。 */
    @GetMapping
    public ApiResponse<List<MaterialView>> list(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId) {
        return ApiResponse.ok(service.list(UserContext.resolve(userId)));
    }

    /**
     * 上传材料。
     *
     * <p>用 {@code multipart/form-data}，字段名 {@code file}，与前端文件选择框一致。
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<MaterialDetailView>> upload(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) throws IOException {

        MaterialService.UploadInput input = new MaterialService.UploadInput(
                file.getOriginalFilename(), file.getContentType(), file.getBytes());
        // 大模型客户端按请求构造：用户自带 token，绝不能做成单例
        AiClient aiClient = aiResolver.resolve(request);
        MaterialDetailView view = service.upload(UserContext.resolve(userId), input, aiClient);
        return ResponseEntity.ok(ApiResponse.ok(view));
    }

    /** 粘贴一段文本作为材料。不经过文件解析，任何情况下都能用。 */
    @PostMapping("/text")
    public ApiResponse<MaterialDetailView> pasteText(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @RequestBody PasteTextRequest body,
            HttpServletRequest request) {

        AiClient aiClient = aiResolver.resolve(request);
        MaterialDetailView view = service.pasteText(
                UserContext.resolve(userId), body.safeTitle(), body.text(), aiClient);
        return ApiResponse.ok(view);
    }

    /** 材料详情：元数据 + 结构化抽取结果。 */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<MaterialDetailView>> detail(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @PathVariable("id") String materialId) {

        return service.find(UserContext.resolve(userId), materialId)
                .map(view -> ResponseEntity.ok(ApiResponse.ok(view)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.fail("找不到这份材料，可能已经被删除了。")));
    }

    /**
     * 抽出的原文。用于「原文对照」面板——让用户自己核对抽取结果，
     * 而不是盲信 AI。这是这个模块可信度的关键。
     */
    @GetMapping(value = "/{id}/text", produces = MediaType.TEXT_PLAIN_VALUE)
    public ResponseEntity<String> originalText(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @PathVariable("id") String materialId) {

        return service.originalText(UserContext.resolve(userId), materialId)
                .map(text -> ResponseEntity.ok()
                        .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                        .body(text))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .contentType(new MediaType(MediaType.TEXT_PLAIN, java.nio.charset.StandardCharsets.UTF_8))
                        .body("这份材料没有可对照的原文。"));
    }

    /** 删除材料（原文件、原文、抽取结果一并删除）。 */
    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<String>> delete(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @PathVariable("id") String materialId) {

        boolean deleted = service.delete(UserContext.resolve(userId), materialId);
        if (!deleted) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("找不到这份材料，可能已经被删除了。"));
        }
        return ResponseEntity.ok(ApiResponse.ok("已删除", "材料已删除"));
    }
}
