package com.shijie.resumehelper.auth.api;

import com.shijie.resumehelper.auth.service.AuthService;
import com.shijie.resumehelper.common.ApiResponse;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 注册、登录与当前用户信息。
 *
 * <p>登录令牌放在响应的 {@code token} 字段，前端之后每次请求带
 * {@code Authorization: Bearer <token>}。服务端不保存会话，退出登录由前端清除令牌即可。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService service;

    public AuthController(AuthService service) {
        this.service = service;
    }

    @PostMapping("/register")
    public ApiResponse<AuthDtos.AuthResponse> register(@Valid @RequestBody AuthDtos.RegisterRequest body) {
        return ApiResponse.ok(toResponse(service.register(body.email(), body.password(), body.displayName())),
                "账号创建成功");
    }

    @PostMapping("/login")
    public ApiResponse<AuthDtos.AuthResponse> login(@Valid @RequestBody AuthDtos.LoginRequest body) {
        return ApiResponse.ok(toResponse(service.login(body.email(), body.password())), "登录成功");
    }

    @GetMapping("/me")
    public ApiResponse<AuthDtos.UserView> me(HttpServletRequest request) {
        String userId = UserContext.resolve(request);
        return ApiResponse.ok(AuthDtos.UserView.from(service.requireUser(userId)));
    }

    @PostMapping("/logout")
    public ApiResponse<String> logout() {
        return ApiResponse.ok("已退出登录");
    }

    private static AuthDtos.AuthResponse toResponse(AuthService.AuthResult result) {
        return new AuthDtos.AuthResponse(
                result.token(),
                result.expiresAt().toString(),
                AuthDtos.UserView.from(result.user()));
    }
}
