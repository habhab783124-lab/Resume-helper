package com.shijie.resumehelper.auth.api;

import com.shijie.resumehelper.auth.domain.UserAccount;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 认证接口的请求 / 响应形状。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank(message = "请填写邮箱") @Email(message = "邮箱格式不正确") String email,
            @NotBlank(message = "请填写密码") String password,
            @Size(max = 24, message = "昵称最多 24 个字") String displayName
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "请填写邮箱") String email,
            @NotBlank(message = "请填写密码") String password
    ) {
    }

    public record UserView(String id, String email, String displayName, String createdAt) {
        public static UserView from(UserAccount account) {
            return new UserView(account.id(), account.email(), account.displayName(), account.createdAt());
        }
    }

    public record AuthResponse(String token, String expiresAt, UserView user) {
    }
}
