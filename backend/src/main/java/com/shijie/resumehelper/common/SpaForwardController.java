package com.shijie.resumehelper.common;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * SPA 前端路由回退。
 *
 * <h2>为什么需要它</h2>
 * 前端是 Vue Router 的 history 模式：<b>磁盘上并不存在</b> {@code /jobs}、{@code /dashboard}
 * 这些路径，它们只是浏览器地址栏里的路由。用户直接访问或刷新
 * {@code http://localhost:8080/jobs} 时，如果没有回退，Spring 会返回 404。
 *
 * <h2>这个类解决什么问题</h2>
 * 课程演示常常<b>不在开发机上</b>做，那台机器可能没有 Node、没有 Maven、不能联网装依赖。
 * 把打包好的前端静态文件（{@code frontend/dist}）交给后端一起提供后，
 * 演示只需要 <b>JDK + 一条 java -jar 命令</b>，而且前后端同源——
 * 不需要 vite 代理、不需要配 CORS。
 *
 * <h2>为什么用显式路由列表而不是通配 {@code /**}</h2>
 * 用 {@code /**} 兜底会把 {@code /api} 也吞掉：写错接口路径时前端会拿到一份 HTML 而不是 404，
 * 排查起来极其痛苦（Vercel 上就是这样，实测 {@code GET /api/...} 返回 200 + text/html）。
 * 这里只列出前端真实存在的路由，{@code /api/**} 完全不碰。
 *
 * <h2>静态文件从哪来</h2>
 * 见 {@code application.yml} 的 {@code spring.web.resources.static-locations}，
 * 默认会找 jar 同级的 {@code webapp/} 目录，也兼容开发时的 {@code frontend/dist/}。
 */
@Controller
public class SpaForwardController {

    /**
     * 与 {@code frontend/src/router/index.js} 里的路由保持一致，新增页面时记得同步。
     *
     * <p>注意：这里必须写成字面量数组，不能引用 {@code static final String[]} 常量——
     * 注解的数组值要求编译期常量，用变量会直接编译失败。
     */
    @GetMapping({"/", "/login", "/dashboard", "/profile", "/materials", "/resume", "/jobs", "/interview", "/report", "/settings"})
    public String forwardToIndex() {
        // forward 会再走一次 DispatcherServlet，由静态资源处理器把 index.html 返回给浏览器；
        // 之后的页面跳转交给前端路由，不再经过后端。
        return "forward:/index.html";
    }
}
