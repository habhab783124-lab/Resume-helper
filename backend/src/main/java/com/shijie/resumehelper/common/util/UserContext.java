package com.shijie.resumehelper.common.util;

/**
 * 当前请求的用户身份。
 *
 * <h2>为什么现在只是一个请求头</h2>
 * 课程演示阶段的登录是纯前端的（{@code frontend/src/utils/demoAuth.js} 里写死了演示账号，
 * 只在浏览器本地校验），后端并没有真正的认证。所以这里先用请求头
 * {@code X-User-Id} 承载身份，缺省为 {@code demo-user}。
 *
 * <h2>接入真实认证时要改什么</h2>
 * 引入 Spring Security + JWT 之后，把 {@link #resolve} 换成从
 * {@code SecurityContextHolder} 取当前用户即可，**所有调用方都不用改**——
 * 这正是把它单独抽出来的原因：不要让"用户是谁"这件事散落在各个 Controller 里。
 *
 * <h2>安全提醒</h2>
 * 请求头是<b>客户端可伪造</b>的。当前没有真实鉴权，所以它只能用于数据分区，
 * <b>不能当作权限控制</b>。接入认证前，不要在后端暴露公网。
 */
public final class UserContext {

    public static final String HEADER = "X-User-Id";

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
        return request == null ? DEFAULT_USER : resolve(request.getHeader(HEADER));
    }
}
