package com.shijie.resumehelper.common.util;

/**
 * 当前请求的用户身份。
 *
 * <h2>身份从哪里来</h2>
 * 真实登录接入后，{@code AuthTokenFilter} 会校验
 * {@code Authorization: Bearer <token>}，把令牌里的用户 id 放进请求属性
 * {@link #AUTHENTICATED_USER}，并包装请求让旧的
 * {@code X-User-Id} 请求头读到同一个值。各业务模块因此不用改。
 *
 * <p>没有令牌时才会退回请求头 {@code X-User-Id}。这条退路只用于
 * {@code app.auth.enforce=false} 的本机课程演示；默认配置会拒绝未登录请求。
 *
 * <h2>安全提醒</h2>
 * 请求头本身是<b>客户端可伪造</b>的。启用认证后，令牌会覆盖请求头；
 * 只有在显式关闭 {@code app.auth.enforce} 时，请求头才会被采信。
 */
public final class UserContext {

    public static final String HEADER = "X-User-Id";

    /** 认证过滤器写入请求属性的键，值是已验签的用户 id。 */
    public static final String AUTHENTICATED_USER = UserContext.class.getName() + ".userId";

    /** 没带请求头时使用的默认用户，和前端 demoProfile 里的 userId 保持一致。 */
    public static final String DEFAULT_USER = "demo-user";

    private UserContext() {
    }

    /** 把请求头的值归一化成用户 id。 */
    public static String resolve(String headerValue) {
        return TextKit.isBlank(headerValue) ? DEFAULT_USER : headerValue.trim();
    }

    /**
     * 从请求里解析用户 id。
     *
     * <h2>为什么所有模块都要走这一个方法</h2>
     * 材料模块和面试模块原来各自认身份：材料模块读请求头，面试模块用
     * {@code request.getSession().getId()}。统一之前，面试模块按 session id
     * 去查材料模块按请求头存的简历，<b>永远查不到</b>——而且不会报错，
     * 只会显示"简历不存在"，非常难排查。
     *
     * <p>身份来源只留这一个入口，就不会再出现两个模块对不上号的情况。
     */
    public static String resolve(jakarta.servlet.http.HttpServletRequest request) {
        if (request == null) {
            return DEFAULT_USER;
        }
        Object authenticated = request.getAttribute(AUTHENTICATED_USER);
        if (authenticated instanceof String userId && !TextKit.isBlank(userId)) {
            return userId.trim();
        }
        return resolve(request.getHeader(HEADER));
    }
}
