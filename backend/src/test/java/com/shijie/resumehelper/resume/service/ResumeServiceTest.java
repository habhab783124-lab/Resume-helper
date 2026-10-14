package com.shijie.resumehelper.resume.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.resume.config.ResumeModuleProperties;
import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeSuggestion;
import com.shijie.resumehelper.resume.store.ResumeVersionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 简历生成的逻辑测试。
 *
 * <h2>重点测什么</h2>
 * 「把画像里每行一条的字符串拆成简历条目」这段逻辑最容易出**静默丢内容**的问题：
 * 拆错了用户不会收到任何报错，只会在打印出来的简历上发现经历少了一段。
 * 所以这里对拆分的边界（没有冒号、冒号在时间里、抬头里没有时间）逐个用例锁住。
 *
 * <p>另外验证 AI 建议的两条底线：编造原文的建议必须被丢掉；没有 token 时不编建议。
 */
class ResumeServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ResumeService service() {
        return new ResumeService(new NoopStore(), new ResumeModuleProperties());
    }

    /** 版本存储在这个测试里用不到，给个空实现即可。 */
    private static final class NoopStore implements ResumeVersionStore {
        @Override public com.shijie.resumehelper.resume.domain.ResumeVersion save(com.shijie.resumehelper.resume.domain.ResumeVersion v) { return v; }
        @Override public Optional<com.shijie.resumehelper.resume.domain.ResumeVersion> find(String u, String i) { return Optional.empty(); }
        @Override public List<com.shijie.resumehelper.resume.domain.ResumeVersion> list(String u) { return List.of(); }
        @Override public int count(String u) { return 0; }
        @Override public boolean delete(String u, String i) { return false; }
    }

    private static AiClient stub(String reply) {
        return new AiClient() {
            @Override public String chat(String systemPrompt, String userPrompt) { return reply; }
            @Override public String name() { return "stub"; }
        };
    }

    private static UserProfileSnapshot profile() {
        return UserProfileSnapshot.builder()
                .userId("demo-user")
                .name("林一舟")
                .phone("138 0000 0000")
                .email("linyizhou@example.com")
                .targetTitle("Java 后端开发工程师")
                .targetCities(List.of("上海"))
                .yearsOfExperience(0)
                .education("本科")
                .educations(List.of("上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06"))
                .skills(List.of("Java", "Spring Boot", "MySQL", "Redis"))
                .industries(List.of("互联网"))
                .expectedSalary("15-22K")
                .summary("计算机专业应届生，有后端实习经历。")
                .experiences(List.of(
                        "上海璞云信息技术有限公司 · Java 后端开发实习生 · 2025.07—2025.09：参与了权限模块开发；把权限校验改成 Redis 缓存",
                        "校园二手交易平台 · 后端负责人 · 2025.03—2025.06：用分布式锁解决了超卖问题"))
                .build();
    }

    // ------------------------------------------------------------------ 拆分

    @Test
    @DisplayName("经历行拆成抬头 / 时间 / 要点")
    void parsesExperienceLine() {
        ResumeDraft.Entry entry = ResumeService.parseExperience(
                "上海璞云信息技术有限公司 · Java 后端开发实习生 · 2025.07—2025.09：参与了权限模块开发；把权限校验改成 Redis 缓存");

        assertThat(entry.title()).isEqualTo("上海璞云信息技术有限公司 · Java 后端开发实习生");
        assertThat(entry.meta()).isEqualTo("2025.07—2025.09");
        assertThat(entry.bullets()).containsExactly("参与了权限模块开发", "把权限校验改成 Redis 缓存");
    }

    @Test
    @DisplayName("没有冒号的经历行整行保留，不丢内容")
    void keepsContentWhenThereIsNoColon() {
        // 这是最危险的情况：拆错了用户不会收到任何提示，只会在打印出来的简历上少一段
        ResumeDraft.Entry entry = ResumeService.parseExperience("一段还没有整理好的经历描述");

        assertThat(entry.title()).isEqualTo("一段还没有整理好的经历描述");
        assertThat(entry.meta()).isEmpty();
        assertThat(entry.bullets()).isEmpty();
    }

    @Test
    @DisplayName("时间里的冒号（10:30）不会被当成分隔符")
    void ignoresColonInsideTime() {
        int index = ResumeService.firstColon("线上故障处理 · 2025.08：10:30 开始排查；11:20 恢复");

        // 命中的应该是"2025.08"后面那个全角冒号，而不是 "10:30" 里的半角冒号
        assertThat("线上故障处理 · 2025.08：10:30 开始排查；11:20 恢复".charAt(index)).isEqualTo('：');
    }

    @Test
    @DisplayName("抬头里没有时间时 meta 为空（不硬塞一个）")
    void noPeriodMeansEmptyMeta() {
        ResumeDraft.Entry entry = ResumeService.parseExperience("校园二手交易平台：用分布式锁解决了超卖问题");

        assertThat(entry.title()).isEqualTo("校园二手交易平台");
        assertThat(entry.meta()).isEmpty();
        assertThat(entry.bullets()).containsExactly("用分布式锁解决了超卖问题");
    }

    @Test
    @DisplayName("教育经历：第一段当抬头，其余当副标题")
    void parsesEducationLine() {
        ResumeDraft.Entry entry = ResumeService.parseEducation("上海理工大学 · 本科 · 计算机科学与技术 · 2022.09—2026.06");

        assertThat(entry.title()).isEqualTo("上海理工大学");
        assertThat(entry.meta()).isEqualTo("本科 · 计算机科学与技术 · 2022.09—2026.06");
    }

    @Test
    @DisplayName("要点按分号拆，不按句号拆（从句号劈开会读不通）")
    void splitsBulletsBySemicolonOnly() {
        assertThat(ResumeService.splitBullets("第一件事。第二件事。"))
                .containsExactly("第一件事。第二件事。");
        assertThat(ResumeService.splitBullets("第一件事；第二件事"))
                .containsExactly("第一件事", "第二件事");
    }

    // ------------------------------------------------------------------ 取材

    @Test
    @DisplayName("按画像生成简历内容")
    void buildsDraftFromProfile() {
        ResumeDraft draft = service().buildDraft(profile());

        assertThat(draft.name()).isEqualTo("林一舟");
        assertThat(draft.headline()).isEqualTo("Java 后端开发工程师");
        assertThat(draft.summary()).contains("计算机专业应届生");
        assertThat(draft.educations()).hasSize(1);
        assertThat(draft.experiences()).hasSize(2);
        assertThat(draft.skills()).contains("Java", "Spring Boot");
        assertThat(draft.keywords()).isNotEmpty();
        // 城市 / 电话 / 邮箱 / 期望薪资都要进抬头——
        // 一份没有联系方式的简历投出去等于白发
        assertThat(draft.contacts()).contains("上海");
        assertThat(draft.contacts()).contains("138 0000 0000");
        assertThat(draft.contacts()).contains("linyizhou@example.com");
        assertThat(draft.contacts().stream().anyMatch(c -> c.contains("15-22K"))).isTrue();
        assertThat(draft.hasContact()).isTrue();
        assertThat(draft.isEmpty()).isFalse();
        // 所有要点拼起来应当覆盖画像里的经历文本（不丢内容）
        assertThat(draft.allBullets()).hasSize(3);
    }

    @Test
    @DisplayName("没填电话邮箱时 hasContact 为 false（简历页据此提示补全）")
    void contactFlagReflectsMissingContactInfo() {
        UserProfileSnapshot withoutContact = UserProfileSnapshot.builder()
                .userId("demo-user")
                .targetTitle("Java 后端开发工程师")
                .skills(List.of("Java"))
                .build();

        ResumeDraft draft = service().buildDraft(withoutContact);

        // 页面上这种缺失很不起眼（抬头那行短一点而已），所以要有一个明确的标志位
        assertThat(draft.hasContact()).isFalse();
    }

    @Test
    @DisplayName("画像为空时给空草稿，而不是抛异常")
    void emptyProfileGivesEmptyDraft() {
        assertThat(service().buildDraft(null).isEmpty()).isTrue();
        assertThat(service().buildDraft(UserProfileSnapshot.builder().build()).isEmpty()).isTrue();
    }

    // ------------------------------------------------------------------ 建议

    @Test
    @DisplayName("original 在简历里找不到的建议会被丢掉（模型编的原文不可信）")
    void dropsSuggestionsWithFabricatedOriginal() {
        String reply = """
                {"suggestions":[
                  {"section":"experiences","index":0,
                   "original":"参与了权限模块开发",
                   "suggested":"负责权限模块的设计与实现，覆盖角色、权限、鉴权三条链路",
                   "reason":"把笼统的「参与」换成具体职责"},
                  {"section":"experiences","index":1,
                   "original":"这段原文简历里根本没有",
                   "suggested":"编出来的建议稿",
                   "reason":"编出来的原因"}
                ]}
                """;

        ResumeService.SuggestResult result = service().suggest(service().buildDraft(profile()), "Java 后端", "", stub(reply));

        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).suggested()).contains("权限模块的设计与实现");
    }

    @Test
    @DisplayName("没有 token 时不编建议，而是说明原因")
    void noTokenDoesNotInventSuggestions() {
        ResumeService.SuggestResult result = service().suggest(service().buildDraft(profile()), "Java 后端", "", null);

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.message()).contains("没有可用的 AI token");
    }

    @Test
    @DisplayName("空简历时不调用大模型，直接告诉用户先去整理材料")
    void emptyDraftSkipsModel() {
        ResumeService.SuggestResult result = service().suggest(
                ResumeDraft.class.cast(service().buildDraft(null)), "Java 后端", "", stub("不应该被调用"));

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.message()).contains("材料整理");
    }

    @Test
    @DisplayName("建议稿与原文相同的不展示（没有采纳价值）")
    void skipsIdenticalSuggestions() {
        String reply = """
                {"suggestions":[
                  {"section":"experiences","index":0,"original":"参与了权限模块开发",
                   "suggested":"参与了权限模块开发","reason":"没改"}
                ]}
                """;

        ResumeService.SuggestResult result = service().suggest(service().buildDraft(profile()), "", "", stub(reply));

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.message()).contains("没有给出可用的建议");
    }

    @Test
    @DisplayName("模型用 markdown 代码块包住 JSON 也能解析")
    void toleratesMarkdownFence() {
        String reply = """
                ```json
                {"suggestions":[{"section":"experiences","index":0,
                 "original":"用分布式锁解决了超卖问题",
                 "suggested":"基于 Redis 分布式锁解决同一商品的并发超卖",
                 "reason":"点明技术手段"}]}
                ```
                """;

        ResumeService.SuggestResult result = service().suggest(service().buildDraft(profile()), "", "", stub(reply));

        assertThat(result.suggestions()).hasSize(1);
        assertThat(result.suggestions().get(0).section()).isEqualTo("experiences");
    }

    @Test
    @DisplayName("模型返回非 JSON 时不崩，给可重试的说明")
    void garbageReplyDoesNotCrash() {
        ResumeService.SuggestResult result = service().suggest(
                service().buildDraft(profile()), "", "", stub("抱歉，我无法完成这个请求。"));

        assertThat(result.suggestions()).isEmpty();
        assertThat(result.message()).isNotEmpty();
    }

    @Test
    @DisplayName("建议的最大条数受配置限制")
    void respectsMaxSuggestions() {
        ResumeModuleProperties properties = new ResumeModuleProperties();
        properties.setMaxSuggestions(1);
        ResumeService limited = new ResumeService(new NoopStore(), properties);

        StringBuilder items = new StringBuilder();
        for (String original : List.of("参与了权限模块开发", "用分布式锁解决了超卖问题")) {
            if (items.length() > 0) {
                items.append(',');
            }
            items.append("{\"section\":\"experiences\",\"index\":0,\"original\":\"").append(original)
                    .append("\",\"suggested\":\"改写后的").append(original).append("\",\"reason\":\"理由\"}");
        }
        String reply = "{\"suggestions\":[" + items + "]}";

        ResumeService.SuggestResult result = limited.suggest(limited.buildDraft(profile()), "", "", stub(reply));

        assertThat(result.suggestions()).hasSize(1);
    }

    /** 保证 MAPPER 真的用得上（后续可能用它做反序列化断言）。 */
    @Test
    @DisplayName("建议对象能被序列化（前端要直接渲染它）")
    void suggestionIsSerializable() throws Exception {
        String json = MAPPER.writeValueAsString(new ResumeSuggestion("experiences", 0, "原文", "建议稿", "原因"));

        assertThat(json).contains("suggested").contains("reason");
    }
}
