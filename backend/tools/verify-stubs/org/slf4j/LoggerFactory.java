package org.slf4j;

/**
 * 仅用于本地校验的 slf4j 打桩工厂，说明见 {@link Logger}。
 *
 * <p>为了让命令行输出可读，这里的日志直接打印到标准错误。
 */
public final class LoggerFactory {

    private LoggerFactory() {
    }

    public static Logger getLogger(Class<?> type) {
        return new ConsoleLogger(type.getSimpleName());
    }

    public static Logger getLogger(String name) {
        return new ConsoleLogger(name);
    }

    private record ConsoleLogger(String name) implements Logger {

        @Override
        public void trace(String format, Object... arguments) {
        }

        @Override
        public void debug(String format, Object... arguments) {
        }

        @Override
        public void info(String format, Object... arguments) {
            print("INFO", format, arguments);
        }

        @Override
        public void warn(String format, Object... arguments) {
            print("WARN", format, arguments);
        }

        @Override
        public void error(String format, Object... arguments) {
            print("ERROR", format, arguments);
        }

        private void print(String level, String format, Object... arguments) {
            // 输出到 stdout：写 stderr 会被 PowerShell 当成 NativeCommandError 渲染成红色报错，
            // 让自检结果看起来像失败了。真实的 Logback 控制台 appender 默认也走 stdout。
            System.out.println("[" + level + "] " + name + " - " + format(format, arguments));
        }

        private static String format(String pattern, Object... arguments) {
            if (arguments == null || arguments.length == 0) {
                return String.valueOf(pattern);
            }
            StringBuilder sb = new StringBuilder();
            int argIndex = 0;
            int i = 0;
            while (i < pattern.length()) {
                int brace = pattern.indexOf("{}", i);
                if (brace < 0 || argIndex >= arguments.length) {
                    sb.append(pattern, i, pattern.length());
                    break;
                }
                sb.append(pattern, i, brace);
                sb.append(arguments[argIndex++]);
                i = brace + 2;
            }
            return sb.toString();
        }
    }
}
