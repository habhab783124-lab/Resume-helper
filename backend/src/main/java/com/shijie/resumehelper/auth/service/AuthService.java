package com.shijie.resumehelper.auth.service;

import com.shijie.resumehelper.auth.AuthException;
import com.shijie.resumehelper.auth.config.AuthProperties;
import com.shijie.resumehelper.auth.domain.UserAccount;
import com.shijie.resumehelper.auth.security.PasswordHasher;
import com.shijie.resumehelper.auth.security.TokenService;
import com.shijie.resumehelper.auth.store.UserStore;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 注册与登录的业务规则。
 */
public class AuthService {

    private static final Pattern EMAIL_PATTERN =
            Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_EMAIL_LENGTH = 254;
    private static final int MAX_DISPLAY_NAME_LENGTH = 24;

    private final UserStore users;
    private final PasswordHasher hasher;
    private final TokenService tokens;
    private final AuthProperties properties;
    private final String dummyHash;

    public AuthService(UserStore users, PasswordHasher hasher, TokenService tokens, AuthProperties properties) {
        this.users = users;
        this.hasher = hasher;
        this.tokens = tokens;
        this.properties = properties;
        // 邮箱不存在时也做一次同样耗时的校验，避免通过响应时间判断邮箱是否注册过。
        this.dummyHash = hasher.hash("not-a-real-password");
    }

    public AuthResult register(String email, String password, String displayName) {
        String normalizedEmail = normalizeEmail(email);
        validateEmail(normalizedEmail);
        validatePassword(password);
        String name = normalizeDisplayName(displayName, normalizedEmail);

        UserAccount account = new UserAccount(
                UUID.randomUUID().toString(),
                normalizedEmail,
                name,
                hasher.hash(password),
                Instant.now().toString());
        if (!users.create(account)) {
            throw new AuthException(HttpStatus.CONFLICT, "这个邮箱已经注册过了，请直接登录。");
        }
        return issue(account);
    }

    public AuthResult login(String email, String password) {
        String normalizedEmail = normalizeEmail(email);
        UserAccount account = users.findByEmail(normalizedEmail).orElse(null);
        if (account == null) {
            // 不暴露“邮箱是否存在”，同时保持相近的校验耗时。
            hasher.matches(password == null ? "" : password, dummyHash);
            throw invalidCredentials();
        }
        if (!hasher.matches(password == null ? "" : password, account.passwordHash())) {
            throw invalidCredentials();
        }
        return issue(account);
    }

    public UserAccount requireUser(String userId) {
        return users.findById(userId)
                .orElseThrow(() -> new AuthException(HttpStatus.UNAUTHORIZED, "登录状态已失效，请重新登录。"));
    }

    private AuthResult issue(UserAccount account) {
        TokenService.IssuedToken token = tokens.issue(account);
        return new AuthResult(token.token(), token.expiresAt(), account);
    }

    private static AuthException invalidCredentials() {
        return new AuthException(HttpStatus.UNAUTHORIZED, "邮箱或密码不正确。");
    }

    private void validateEmail(String email) {
        if (email.isEmpty() || email.length() > MAX_EMAIL_LENGTH || !EMAIL_PATTERN.matcher(email).matches()) {
            throw new AuthException(HttpStatus.BAD_REQUEST, "请输入正确的邮箱地址。");
        }
    }

    private void validatePassword(String password) {
        if (password == null || password.length() < properties.getPasswordMinLength()) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "密码至少需要 " + properties.getPasswordMinLength() + " 位。");
        }
        if (password.length() > properties.getPasswordMaxLength()) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "密码太长，请控制在 " + properties.getPasswordMaxLength() + " 位以内。");
        }
    }

    private static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    private static String normalizeDisplayName(String displayName, String email) {
        String name = displayName == null ? "" : displayName.trim();
        if (name.isEmpty()) {
            int at = email.indexOf('@');
            name = at > 0 ? email.substring(0, at) : email;
        }
        if (name.codePointCount(0, name.length()) > MAX_DISPLAY_NAME_LENGTH) {
            throw new AuthException(HttpStatus.BAD_REQUEST,
                    "昵称最多 " + MAX_DISPLAY_NAME_LENGTH + " 个字。");
        }
        return name;
    }

    public record AuthResult(String token, Instant expiresAt, UserAccount user) {
    }
}
