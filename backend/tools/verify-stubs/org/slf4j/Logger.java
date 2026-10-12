package org.slf4j;

/**
 * 仅用于本地校验的 slf4j 打桩接口。
 *
 * <p>这不是项目代码：它放在 {@code backend/tools/verify-stubs} 下，不在 {@code src/main/java} 里，
 * 不会被 Maven 打包。用途是在没有网络、装不了 Maven 的机器上，
 * 用 javac 单独编译 job 模块的 core 层（纯逻辑 + slf4j 日志调用），验证逻辑正确性。
 * 真实构建时请使用 Spring Boot 自带的 slf4j。
 */
public interface Logger {

    void trace(String format, Object... arguments);

    void debug(String format, Object... arguments);

    void info(String format, Object... arguments);

    void warn(String format, Object... arguments);

    void error(String format, Object... arguments);
}
