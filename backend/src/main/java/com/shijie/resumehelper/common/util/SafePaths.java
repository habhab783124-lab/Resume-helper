package com.shijie.resumehelper.common.util;

import java.nio.file.Path;
import java.util.List;

/**
 * 把用户可控的字符串安全地变成文件路径片段。
 *
 * <h2>为什么需要这个类</h2>
 * 材料存储会把 {@code userId} 和 {@code materialId} 拼进目录名。
 * 这两个值来自请求，如果直接拼，攻击者传一个
 * {@code userId = "../../../../Windows"} 就能把文件写到程序目录外面去
 * （目录穿越 / path traversal）。
 *
 * <p>这里做两道防线：
 * <ol>
 *   <li>{@link #safeSegment} 把片段清洗成只含安全字符的单层名字；</li>
 *   <li>{@link #resolveInside} 在拼完路径后<b>再确认一次</b>结果确实落在根目录之下。
 *       就算第 1 步有疏漏，也写不出去。</li>
 * </ol>
 *
 * <p>安全上"两层独立校验"比"一层做得很仔细"可靠得多。
 */
public final class SafePaths {

    /** 片段最大长度。避免有人用超长名字把文件系统撑爆。 */
    private static final int MAX_SEGMENT_LENGTH = 64;

    private SafePaths() {
    }

    /**
     * 清洗成一个安全的单层路径片段。
     *
     * <p>白名单策略：只保留字母、数字、点、下划线、连字符，其余一律换成下划线。
     * 白名单比黑名单可靠——不用去想还有哪些字符没考虑到。
     *
     * <h2>为什么清洗有损时要补一个哈希后缀</h2>
     * 中文、日文、俄文等非 ASCII 字符会被**整体**替换成下划线。
     * 如果不做区分，{@code "用户名A"} 和 {@code "用户名B"} 都会变成 {@code "____"}，
     * 于是两个用户落到同一个存储目录里，<b>数据就混了</b>——这是数据隔离事故，
     * 不是"名字难看一点"。
     *
     * <p>所以只要清洗过程丢掉了信息，就在末尾补一个原串的短哈希：
     * {@code "用户名A" -> "____-3f2a91"}。可读性差一点，但保证不同输入映射到不同目录。
     */
    public static String safeSegment(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String original = raw.trim();
        if (original.isEmpty() || ".".equals(original) || "..".equals(original)) {
            return fallback;
        }

        String cleaned = original.replaceAll("[^A-Za-z0-9._-]", "_");
        // 干掉连续的点：.. 是目录穿越的核心，... 之类的变体也一起处理
        cleaned = cleaned.replaceAll("\\.{2,}", "_");
        // 去掉开头的点：避免生成隐藏文件/目录
        while (cleaned.startsWith(".")) {
            cleaned = cleaned.substring(1);
        }
        if (cleaned.isEmpty()) {
            return fallback;
        }

        boolean lossy = !cleaned.equals(original);
        String suffix = lossy ? "-" + shortHash(original) : "";
        int keep = Math.max(1, MAX_SEGMENT_LENGTH - suffix.length());
        if (cleaned.length() > keep) {
            cleaned = cleaned.substring(0, keep);
        }
        return cleaned + suffix;
    }

    /**
     * 原串的短哈希，用来在清洗有损时保持唯一性。
     *
     * <p>用 {@link String#hashCode()} 而不是 SHA-256：这里只要"不同输入不撞车"，
     * 不需要密码学强度。32 位哈希对几十上百个用户量级的碰撞概率可以忽略，
     * 而且不引入任何依赖、开销也极小。
     */
    private static String shortHash(String value) {
        return Integer.toHexString(value.hashCode() & 0x7fffffff);
    }

    /**
     * 在 {@code root} 之下解析路径，并确认结果没有跑到 root 外面。
     *
     * <p>必须逐个片段检查，不能只检查最后的结果：{@link Path#resolve} 遇到绝对路径片段时
     * 会<b>直接丢掉前面的基准路径</b>，也就是说 {@code resolve("C:\\Windows")} 得到的就是
     * {@code C:\Windows}，根本不经过 root。
     *
     * @throws IllegalArgumentException 片段非法或结果越界
     */
    public static Path resolveInside(Path root, String... segments) {
        Path base = root.toAbsolutePath().normalize();
        Path target = base;
        for (String segment : segments) {
            requireSingleSegment(segment);
            target = target.resolve(segment);
        }
        target = target.normalize();
        if (!target.startsWith(base)) {
            throw new IllegalArgumentException("路径越界，已拒绝：" + target);
        }
        return target;
    }

    private static void requireSingleSegment(String segment) {
        if (segment == null || segment.isEmpty()) {
            throw new IllegalArgumentException("路径片段不能为空");
        }
        if (segment.contains("/") || segment.contains("\\") || segment.contains(":")) {
            throw new IllegalArgumentException("路径片段不能包含分隔符：" + segment);
        }
        if (".".equals(segment) || "..".equals(segment)) {
            throw new IllegalArgumentException("路径片段不能是相对路径标记：" + segment);
        }
    }

    /** 便捷方法：一次校验多个片段。 */
    public static Path resolveInside(Path root, List<String> segments) {
        return resolveInside(root, segments.toArray(String[]::new));
    }
}
