package com.shijie.resumehelper.material;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.ai.AiClient;
import com.shijie.resumehelper.material.domain.ExtractedProfile;
import com.shijie.resumehelper.material.extract.ProfileExtractor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 结构化抽取的行为测试。
 *
 * <h2>为什么用桩客户端而不是真的调大模型</h2>
 * 这里要验证的是<b>我们自己的逻辑</b>：提示词之外的那几道防线——
 * 证据回验会不会真的丢掉编造的经历、模型返回脏 JSON 时会不会崩、
 * 没有 token 时会不会瞎编。
 *
 * <p>这些用预置的返回文本就能测，而且比调真实模型更可靠：
 * 真实模型每次输出都不一样，测不出稳定结论。真实模型的**抽取质量**
 * 需要另跑一次真实调用人工核对（见 tools/material-check.ps1 的 -ApiKey 参数）。
 */
class ProfileExtractorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 一份足够真实的简历原文，evidence 片段都从这里取。 */
    private static final String SOURCE = """
            林一舟
            求职意向：Java 后端开发工程师 | 期望城市：上海
            教育经历
            学校 专业 学历 时间
            上海理工大学 计算机科学与技术 本科 2022.09 - 2026.06
            实习经历
            上海璞云信息技术有限公司 | Java 后端开发实习生 | 2025.07 - 2025.09
            参与公司 SaaS 后台的用户权限模块开发，用 Spring Boot + MyBatis 实现角色与权限的增删改查
            项目经历
            校园二手交易平台 | 后端负责人 | 2025.03 - 2025.06
            设计基于 Redis 的分布式锁，解决了同一件商品被两个用户同时下单的超卖问题
            专业技能
            编程语言：Java（熟练）、SQL
            框架：Spring Boot、Spring MVC、MyBatis
            数据库：MySQL、Redis
            """;

    private static AiClient stub(String reply) {
        return new AiClient() {
            @Override
            public String chat(String systemPrompt, String userPrompt) {
                return reply;
            }

            @Override
            public String name() {
                return "stub";
            }
        };
    }

    private static ProfileExtractor extractor(AiClient client) {
        return new ProfileExtractor(client, MAPPER, 12000);
    }

    @Test
    @DisplayName("模型编造的经历会被证据回验丢掉")
    void fabricatedEntryIsDropped() {
        // 第二条实习经历在原文里根本不存在，它的 evidence 也是编的
        String reply = """
                {
                  "summary": "计算机专业应届生，有后端实习经历。",
                  "highestEducation": "本科",
                  "yearsOfExperience": 0,
                  "educations": [
                    {"school":"上海理工大学","major":"计算机科学与技术","degree":"本科",
                     "period":"2022.09 - 2026.06",
                     "evidence":"上海理工大学 计算机科学与技术 本科 2022.09 - 2026.06"}
                  ],
                  "experiences": [
                    {"company":"上海璞云信息技术有限公司","role":"Java 后端开发实习生",
                     "period":"2025.07 - 2025.09","description":"参与权限模块开发",
                     "evidence":"上海璞云信息技术有限公司 | Java 后端开发实习生 | 2025.07 - 2025.09"},
                    {"company":"字节跳动","role":"后端开发实习生","period":"2024.06 - 2024.09",
                     "description":"参与推荐系统召回链路优化",
                     "evidence":"在字节跳动实习期间参与抖音推荐系统开发，优化了召回链路"}
                  ],
                  "projects": [],
                  "skills": ["Java","Spring Boot","MySQL","Redis"],
                  "highlights": []
                }
                """;

        ExtractedProfile profile = extractor(stub(reply)).extract(SOURCE, "sample-resume.docx");

        // 教育经历是真的，保留
        assertThat(profile.educations()).hasSize(1);
        assertThat(profile.educations().get(0).school()).isEqualTo("上海理工大学");

        // 编造的那条必须被丢掉，只留下原文里真有的那条
        assertThat(profile.experiences()).hasSize(1);
        assertThat(profile.experiences().get(0).company()).isEqualTo("上海璞云信息技术有限公司");
        assertThat(profile.experiences())
                .noneMatch(e -> e.company().contains("字节"))
                .noneMatch(e -> e.company().contains("璞云") && e.description().contains("推荐系统"));

        // 而且要有提示，让用户知道系统主动丢过东西
        assertThat(profile.warnings()).anyMatch(w -> w.contains("找不到对应内容"));

        assertThat(profile.skills()).contains("Java", "Spring Boot", "MySQL", "Redis");
        assertThat(profile.highestEducation()).isEqualTo("本科");
    }

    @Test
    @DisplayName("原文里的证据被重新排版过（空格换行不同）仍然算通过")
    void evidenceToleratesWhitespaceDifference() {
        String reply = """
                {
                  "summary": "",
                  "highestEducation": "",
                  "yearsOfExperience": 0,
                  "educations": [],
                  "experiences": [
                    {"company":"上海璞云信息技术有限公司","role":"","period":"",
                     "description":"",
                     "evidence":"上海璞云信息技术有限公司   |   Java 后端开发实习生"}
                  ],
                  "projects": [],
                  "skills": [],
                  "highlights": []
                }
                """;

        ExtractedProfile profile = extractor(stub(reply)).extract(SOURCE, "x.docx");

        // 只是空格不同，不该被误杀——否则会因为模型的排版习惯丢掉真实经历
        assertThat(profile.experiences()).hasSize(1);
        assertThat(profile.warnings()).noneMatch(w -> w.contains("找不到对应内容"));
    }

    @Test
    @DisplayName("没有可用 token 时不猜测，返回空结果并说明原因")
    void noTokenDoesNotGuess() {
        ExtractedProfile profile = extractor(null).extract(SOURCE, "x.docx");

        assertThat(profile.isEmpty()).isTrue();
        assertThat(profile.skills()).isEmpty();
        assertThat(profile.warnings()).anyMatch(w -> w.contains("没有可用的 AI token"));
    }

    @Test
    @DisplayName("模型用 markdown 代码块包住 JSON 也能解析")
    void toleratesMarkdownCodeFence() {
        String reply = """
                ```json
                {"summary":"应届生","highestEducation":"本科","yearsOfExperience":0,
                 "educations":[],"experiences":[],"projects":[],
                 "skills":["Java"],"highlights":[]}
                ```
                """;

        ExtractedProfile profile = extractor(stub(reply)).extract(SOURCE, "x.docx");

        assertThat(profile.skills()).containsExactly("Java");
        assertThat(profile.summary()).isEqualTo("应届生");
    }

    @Test
    @DisplayName("模型返回的不是 JSON 时不崩溃，而是给出可重试的提示")
    void garbageReplyDoesNotCrash() {
        ExtractedProfile profile = extractor(stub("抱歉，我无法完成这个请求。")).extract(SOURCE, "x.docx");

        assertThat(profile.isEmpty()).isTrue();
        assertThat(profile.warnings()).anyMatch(w -> w.contains("格式不对"));
    }

    @Test
    @DisplayName("模型把字段类型写错（年限返回成字符串）也能读出来")
    void toleratesWrongFieldTypes() {
        String reply = """
                {"summary":"","highestEducation":"","yearsOfExperience":"1 年",
                 "educations":[],"experiences":[],"projects":[],
                 "skills":"Java,Spring Boot","highlights":[]}
                """;

        ExtractedProfile profile = extractor(stub(reply)).extract(SOURCE, "x.docx");

        assertThat(profile.yearsOfExperience()).isEqualTo(1);
        // skills 给的是字符串而不是数组：解析不出来时不要崩，退化成空列表即可
        assertThat(profile.skills()).isNotNull();
    }

    @Test
    @DisplayName("大模型调用抛异常时降级为提示，不把异常抛给上层")
    void aiFailureDegradesGracefully() {
        AiClient failing = new AiClient() {
            @Override
            public String chat(String systemPrompt, String userPrompt) {
                throw new AiException("401 鉴权失败", false);
            }

            @Override
            public String name() {
                return "failing";
            }
        };

        ExtractedProfile profile = extractor(failing).extract(SOURCE, "x.docx");

        assertThat(profile.isEmpty()).isTrue();
        assertThat(profile.warnings()).anyMatch(w -> w.contains("调用大模型失败"));
    }

    @Test
    @DisplayName("离线替身（FakeAiClient 那种）不会被当成真模型用来抽取")
    void fallbackClientIsNotUsed() {
        AiClient fallback = new AiClient() {
            @Override
            public String chat(String systemPrompt, String userPrompt) {
                return "{\"summary\":\"不该被用到\"}";
            }

            @Override
            public boolean isFallback() {
                return true;
            }
        };

        ExtractedProfile profile = extractor(fallback).extract(SOURCE, "x.docx");

        assertThat(profile.summary()).isEmpty();
        assertThat(profile.warnings()).anyMatch(w -> w.contains("没有可用的 AI token"));
    }
}
