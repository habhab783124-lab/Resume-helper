package com.shijie.resumehelper.auth.security;

import com.shijie.resumehelper.auth.config.AuthProperties;
import com.shijie.resumehelper.common.util.UserContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;

/**
 * 从 {@code Authorization: Bearer <token>} 解析登录用户。
 *
 * <p>解析成功后会把请求包装一层，让原有的
 * {@code @RequestHeader("X-User-Id")} 拿到令牌里的用户 id。
 * 这样材料、简历、面试等模块一行都不用改，客户端也无法再通过伪造请求头
 * 冒充别人。
 */
public class AuthTokenFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final AuthProperties properties;

    public AuthTokenFilter(TokenService tokens, AuthProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // MockMvc 下 getServletPath() 可能为空，所以从 requestURI 里手动去掉 context-path。
        String path = request.getRequestURI();
        String contextPath = request.getContextPath();
        if (contextPath != null && !contextPath.isEmpty() && path.startsWith(contextPath)) {
            path = path.substring(contextPath.length());
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        // 注册/登录本身不需要令牌；语音 WebSocket 用自己的 ticket 鉴权。
        return path.equals("/api/auth/login")
                || path.equals("/api/auth/register")
                || path.equals("/api/interview-voice/ws");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String token = bearerToken(request);
        if (token != null) {
            Optional<TokenService.TokenClaims> claims = tokens.verify(token);
            if (claims.isEmpty()) {
                unauthorized(response, "登录已过期，请重新登录。");
                return;
            }
            request.setAttribute(UserContext.AUTHENTICATED_USER, claims.get().userId());
            chain.doFilter(new AuthenticatedRequest(request, claims.get().userId()), response);
            return;
        }

        if (properties.isEnforce()) {
            unauthorized(response, "请先登录后再访问。");
            return;
        }
        chain.doFilter(request, response);
    }

    private static String bearerToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        return token.isEmpty() ? null : token;
    }

    private static void unauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"success\":false,\"message\":\"" + message + "\",\"data\":null}");
    }

    /** 让旧代码继续读 X-User-Id，但值来自已验签的令牌，而不是客户端请求头。 */
    private static final class AuthenticatedRequest extends HttpServletRequestWrapper {

        private final String userId;

        private AuthenticatedRequest(HttpServletRequest request, String userId) {
            super(request);
            this.userId = userId;
        }

        @Override
        public String getHeader(String name) {
            return UserContext.HEADER.equalsIgnoreCase(name) ? userId : super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            return UserContext.HEADER.equalsIgnoreCase(name)
                    ? Collections.enumeration(List.of(userId))
                    : super.getHeaders(name);
        }
    }
}
