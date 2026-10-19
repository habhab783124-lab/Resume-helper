package com.shijie.resumehelper.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.auth.config.AuthProperties;
import com.shijie.resumehelper.auth.domain.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;

/**
 * 无状态登录令牌（HS256 JWT）。
 *
 * <p>不引入 JWT 库：签名、过期校验都是标准库二十行就能完成的事。
 * 令牌只包含用户 id / 邮箱和过期时间，不包含密码或任何敏感信息。
 */
public final class TokenService {

    private static final Logger log = LoggerFactory.getLogger(TokenService.class);
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();
    private static final String HEADER = ENCODER.encodeToString(
            "{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));

    private final ObjectMapper mapper;
    private final byte[] secret;
    private final Duration ttl;

    public TokenService(AuthProperties properties, ObjectMapper mapper) {
        this.mapper = mapper;
        this.ttl = Duration.ofDays(Math.max(1, properties.getTokenTtlDays()));
        this.secret = resolveSecret(properties.getTokenSecret());
    }

    public IssuedToken issue(UserAccount user) {
        long now = Instant.now().getEpochSecond();
        long expiresAt = now + ttl.getSeconds();
        try {
            String payload = ENCODER.encodeToString(mapper.writeValueAsBytes(
                    new ClaimsPayload(user.id(), user.email(), now, expiresAt)));
            String signingInput = HEADER + "." + payload;
            String signature = ENCODER.encodeToString(sign(signingInput));
            return new IssuedToken(signingInput + "." + signature, Instant.ofEpochSecond(expiresAt));
        } catch (Exception e) {
            throw new IllegalStateException("生成登录令牌失败", e);
        }
    }

    public Optional<TokenClaims> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) {
                return Optional.empty();
            }
            String signingInput = parts[0] + "." + parts[1];
            byte[] expected = sign(signingInput);
            byte[] actual = DECODER.decode(parts[2]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            ClaimsPayload payload = mapper.readValue(DECODER.decode(parts[1]), ClaimsPayload.class);
            if (payload.sub() == null || payload.sub().isBlank()
                    || payload.exp() <= Instant.now().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new TokenClaims(payload.sub(), payload.email(),
                    payload.iat(), payload.exp()));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private byte[] sign(String input) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(input.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("令牌签名失败", e);
        }
    }

    private static byte[] resolveSecret(String configured) {
        if (configured == null || configured.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("未配置 app.auth.token-secret，已生成临时随机密钥；服务重启后已登录用户需要重新登录。");
            return random;
        }
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(configured.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("初始化令牌密钥失败", e);
        }
    }

    public record IssuedToken(String token, Instant expiresAt) {
    }

    public record TokenClaims(String userId, String email, long issuedAt, long expiresAt) {
    }

    private record ClaimsPayload(String sub, String email, long iat, long exp) {
    }
}
