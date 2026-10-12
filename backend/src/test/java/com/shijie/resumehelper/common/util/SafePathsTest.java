package com.shijie.resumehelper.common.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 路径安全测试。
 *
 * <p>材料存储会把 {@code userId} / {@code materialId} 拼进目录名，
 * 而这两个值来自请求。如果不做清洗，攻击者传
 * {@code userId = "../../../../Windows"} 就能把上传的文件写到程序目录之外。
 *
 * <p>这类漏洞在"能跑通就行"的课程项目里极常见，也是把项目变成真产品时
 * 最容易出事的地方——所以单独用测试锁住行为。
 */
class SafePathsTest {

    @Test
    @DisplayName("目录穿越的片段会被清洗掉，不留分隔符也不留 ..")
    void traversalSegmentsAreSanitized(@TempDir Path root) {
        for (String nasty : new String[]{
                "../../../../Windows",
                "..\\..\\etc",
                "a/b/c",
                "C:\\Windows\\System32",
                "....//....//x"}) {
            String cleaned = SafePaths.safeSegment(nasty, "demo");
            assertThat(cleaned)
                    .as("清洗 %s 的结果", nasty)
                    .doesNotContain("/")
                    .doesNotContain("\\")
                    .doesNotContain("..")
                    .doesNotContain(":")
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("不同的非 ASCII 用户 id 必须映射到不同目录（否则两个用户的数据会混在一起）")
    void nonAsciiIdsDoNotCollide(@TempDir Path root) {
        // 这一条是整个类里最重要的断言。
        // 如果只把非 ASCII 字符换成下划线而不做区分：
        //   "用户名A" -> "____"
        //   "用户名B" -> "____"
        // 两个用户就落到同一个存储目录，读取时互相能看到对方的简历。
        String first = SafePaths.safeSegment("用户名A", "demo-user");
        String second = SafePaths.safeSegment("用户名B", "demo-user");

        assertThat(first).isNotEqualTo(second);
        assertThat(SafePaths.resolveInside(root, first))
                .isNotEqualTo(SafePaths.resolveInside(root, second));

        // 同一个输入必须稳定映射到同一个目录，否则重启后就找不到自己的材料了
        assertThat(SafePaths.safeSegment("用户名A", "demo-user")).isEqualTo(first);
    }

    @Test
    @DisplayName("普通 ASCII 用户 id 保持可读，不会被加上哈希后缀")
    void asciiIdsStayReadable() {
        assertThat(SafePaths.safeSegment("demo-user", "fallback")).isEqualTo("demo-user");
        assertThat(SafePaths.safeSegment("mat-abc123", "fallback")).isEqualTo("mat-abc123");
        assertThat(SafePaths.safeSegment("user_01", "fallback")).isEqualTo("user_01");
    }

    @Test
    @DisplayName("空值、空白与相对路径标记退回默认值")
    void emptyFallsBackToDefault() {
        assertThat(SafePaths.safeSegment(null, "demo-user")).isEqualTo("demo-user");
        assertThat(SafePaths.safeSegment("   ", "demo-user")).isEqualTo("demo-user");
        assertThat(SafePaths.safeSegment(".", "demo-user")).isEqualTo("demo-user");
        assertThat(SafePaths.safeSegment("..", "demo-user")).isEqualTo("demo-user");
    }

    @Test
    @DisplayName("片段过长会被截断，不会把文件系统撑爆")
    void overlongSegmentsAreTruncated() {
        String cleaned = SafePaths.safeSegment("a".repeat(500), "demo");

        assertThat(cleaned.length()).isLessThanOrEqualTo(64);
    }

    @Test
    @DisplayName("相对路径标记会被拒绝，而不是被静默接受")
    void relativeMarkersAreRejected(@TempDir Path root) {
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "..", "evil"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("相对路径标记");
    }

    @Test
    @DisplayName("绝对路径片段会被拒绝：Path.resolve 遇到绝对路径会丢掉基准目录")
    void absoluteSegmentsAreRejected(@TempDir Path root) {
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "C:\\Windows\\System32"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "user", "/etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("空片段会被拒绝")
    void emptySegmentsAreRejected(@TempDir Path root) {
        assertThatThrownBy(() -> SafePaths.resolveInside(root, ""))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SafePaths.resolveInside(root, "user", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("正常片段能解析，且结果确实在根目录之下")
    void normalSegmentsResolveInsideRoot(@TempDir Path root) {
        Path resolved = SafePaths.resolveInside(root, "demo-user", "materials", "mat-abc123");

        // 用布尔断言而不是 AssertJ 的 Path 断言：
        // 后者内部会调 toRealPath()，路径不存在时会抛 NoSuchFileException
        assertThat(resolved.startsWith(root.toAbsolutePath().normalize())).isTrue();
        assertThat(resolved.getFileName().toString()).isEqualTo("mat-abc123");
    }

    @Test
    @DisplayName("清洗后的恶意片段参与解析，也跑不出根目录")
    void sanitizedSegmentsStayInsideRoot(@TempDir Path root) {
        String nasty = SafePaths.safeSegment("../../../../Windows", "demo");
        Path resolved = SafePaths.resolveInside(root, nasty);

        assertThat(resolved.startsWith(root.toAbsolutePath().normalize())).isTrue();
    }
}
