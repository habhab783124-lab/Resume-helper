package com.shijie.resumehelper.material.api;

import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.material.service.MaterialService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户求职画像接口。对应产品方案 8 的 {@code PUT /api/profile}
 * 「保存确认后的个人资料」。
 *
 * <h2>为什么放在材料模块里</h2>
 * 画像的**来源**就是材料：用户上传简历 → 系统抽取 → 用户确认/修改 → 存成画像。
 * 所以它天然属于这个模块。而岗位模块只<b>读</b>画像（{@code UserProfileSnapshot}
 * 已经提到 {@code common.profile} 供两个模块共用）。
 *
 * <p>接入真实认证后，{@code userId} 会由认证信息决定，客户端无法再自己指定。
 * 现在的演示阶段只能靠请求头，见 {@link UserContext} 的安全提醒。
 */
@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final MaterialService service;

    public ProfileController(MaterialService service) {
        this.service = service;
    }

    /** 读取已保存的画像。还没确认过时返回 404，前端据此提示"去整理材料"。 */
    @GetMapping
    public ResponseEntity<ApiResponse<UserProfileSnapshot>> get(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId) {

        String resolved = UserContext.resolve(userId);
        return service.findProfile(resolved)
                .map(profile -> ResponseEntity.ok(ApiResponse.ok(profile)))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.fail("还没有保存过求职画像。上传一份材料并确认，或手动填写后再保存。")));
    }

    /**
     * 保存用户确认后的画像。
     *
     * <p>请求体字段与岗位接口 {@code /api/jobs/search} 里的 {@code profile} 一致，
     * 前端确认完可以直接透传，不用做字段映射。
     */
    @PutMapping
    public ApiResponse<UserProfileSnapshot> save(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId,
            @RequestBody UserProfileSnapshot body) {

        String resolved = UserContext.resolve(userId);
        // 以服务端解析出的用户为准，忽略请求体里可能带的 userId，
        // 否则客户端可以伪造别人的身份去覆盖资料
        //
        // 用 Builder 而不是全参构造：字段已经 14 个，位置参数排错时根本看不出来
        // （这次加 name / phone / email 就差点把顺序搞错）。
        UserProfileSnapshot profile = UserProfileSnapshot.builder()
                .userId(resolved)
                .name(body.name())
                .phone(body.phone())
                .email(body.email())
                .targetTitle(body.targetTitle())
                .targetCities(body.targetCities())
                .yearsOfExperience(body.yearsOfExperience())
                .education(body.education())
                .educations(body.educations())
                .skills(body.skills())
                .industries(body.industries())
                .expectedSalary(body.expectedSalary())
                .summary(body.summary())
                .experiences(body.experiences())
                .build();
        service.saveProfile(resolved, profile);
        return ApiResponse.ok(profile, "求职画像已保存");
    }

    /** 清空画像。 */
    @DeleteMapping
    public ApiResponse<String> clear(
            @RequestHeader(value = UserContext.HEADER, required = false) String userId) {

        boolean deleted = service.findProfile(UserContext.resolve(userId))
                .map(profile -> {
                    service.saveProfile(profile.userId(), emptyProfile(profile.userId()));
                    return true;
                })
                .orElse(false);
        return ApiResponse.ok(deleted ? "画像已清空" : "本来就没有保存过画像");
    }

    private UserProfileSnapshot emptyProfile(String userId) {
        // 用 Builder 而不是全参构造：字段一多，位置参数排错时根本看不出来
        return UserProfileSnapshot.builder()
                .userId(userId)
                .yearsOfExperience(0)
                .build();
    }
}
