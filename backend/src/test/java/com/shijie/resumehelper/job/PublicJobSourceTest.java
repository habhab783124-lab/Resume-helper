package com.shijie.resumehelper.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.source.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class PublicJobSourceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void officialListingsKeepSourceAndUnknownSalaryAndRejectInvalidPosts() throws Exception {
        var posts = json.readTree("""
                [{"PostId":"12345","RecruitPostName":"Java 后端开发","LocationName":"深圳",
                "Responsibility":"使用 Java 和 Spring Boot 开发后端服务","RequireWorkYearsName":"三年以上工作经验","IsValid":true},
                {"PostId":"bad/id","RecruitPostName":"无效链接"},
                {"PostId":"99","RecruitPostName":"已下架","IsValid":false}]
                """);
        var jobs = new TencentJobSource().parse(posts);
        assertThat(jobs).hasSize(1);
        assertThat(jobs.get(0).sourceUrl()).isEqualTo("https://careers.tencent.com/jobdesc.html?postId=12345");
        assertThat(jobs.get(0).salary()).isEqualTo("面议");
        assertThat(jobs.get(0).education()).isEqualTo("未披露");
        assertThat(jobs.get(0).minYears()).isEqualTo(3);
        assertThat(jobs.get(0).skills()).contains("Java", "Spring Boot");
    }

    @Test void searchesAreEncodedAndCachedWithoutSendingAnyResumeText() throws Exception {
        var calls = new AtomicInteger();
        var source = new TencentJobSource(new PublicJobJsonClient() {
            @Override public JsonNode get(URI uri) throws Exception {
                calls.incrementAndGet();
                assertThat(uri.getHost()).isEqualTo("careers.tencent.com");
                assertThat(uri.getRawQuery()).contains("keyword=C%2B%2B");
                return json.readTree("{\"Code\":200,\"Data\":{\"Posts\":[{\"PostId\":\"1\",\"RecruitPostName\":\"C++ 开发\"}]}}");
            }
        });
        source.search(new JobQuery().keyword("C++")); source.search(new JobQuery().keyword("C++"));
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test void twoYearsOfExperienceInChineseIsNotMisreadAsZero() throws Exception {
        var jobs = new TencentJobSource().parse(json.readTree("[{\"PostId\":\"2\",\"RecruitPostName\":\"Java\",\"RequireWorkYearsName\":\"两年以上工作经验\"}]"));
        assertThat(jobs.get(0).minYears()).isEqualTo(2);
        assertThat(new JobQuery().maxMinYears(1).accepts(jobs.get(0))).isFalse();
    }

    @Test void officialSearchLoadsLaterPagesBeforeApplyingCityAndExperienceFilters() throws Exception {
        var pages = new java.util.ArrayList<Integer>();
        var source = new TencentJobSource(new PublicJobJsonClient() {
            @Override public JsonNode get(URI uri) {
                int page = uri.getRawQuery().contains("pageIndex=2") ? 2 : 1;
                pages.add(page);
                var root = json.createObjectNode(); root.put("Code", 200);
                var data = root.putObject("Data"); data.put("Count", 101);
                var posts = data.putArray("Posts");
                for (int i = 0; i < (page == 1 ? 100 : 1); i++) {
                    var post = posts.addObject(); post.put("PostId", String.valueOf(page * 1000 + i));
                    post.put("RecruitPostName", "Java 工程师 " + page + "-" + i);
                    post.put("LocationName", page == 1 ? "深圳" : "上海");
                    post.put("RequireWorkYearsName", page == 1 ? "五年以上" : "一年以上");
                }
                return root;
            }
        });
        var query = new JobQuery().keyword("Java").city("上海").strictCity(true).maxMinYears(1).limit(200);
        var result = new JobSourceRegistry(List.of(source)).search(query);
        assertThat(pages).containsExactly(1, 2);
        assertThat(result.jobs()).hasSize(1);
        source.search(query);
        assertThat(pages).hasSize(2);
    }

    @Test void companyQueriesFallBackToPublicListingsButNeverReturnUnrelatedRows() throws Exception {
        var calls = new java.util.ArrayList<String>();
        var source = new TencentJobSource(new PublicJobJsonClient() {
            @Override public JsonNode get(URI uri) throws Exception {
                calls.add(uri.getRawQuery());
                return uri.getRawQuery().contains("keyword=&")
                        ? json.readTree("{\"Code\":200,\"Data\":{\"Count\":1,\"Posts\":[{\"PostId\":\"1\",\"RecruitPostName\":\"Java\",\"ComName\":\"腾讯云智\"}]}}")
                        : json.readTree("{\"Code\":200,\"Data\":{\"Count\":0,\"Posts\":[]}}");
            }
        });
        var registry = new JobSourceRegistry(List.of(source));
        assertThat(registry.search(new JobQuery().keyword("腾讯云智")).jobs()).hasSize(1);
        assertThat(registry.search(new JobQuery().keyword("不存在的公司")).jobs()).isEmpty();
        assertThat(calls).hasSize(3);
    }

    @Test void officialKeywordHitsAreNotRejectedForMissingSummaryRequirementsOrInventedAsSkills() throws Exception {
        var source = new TencentJobSource(new PublicJobJsonClient() {
            @Override public JsonNode get(URI uri) throws Exception {
                return json.readTree("{\"Code\":200,\"Data\":{\"Count\":1,\"Posts\":[{\"PostId\":\"1\",\"RecruitPostName\":\"后台开发\",\"Responsibility\":\"负责业务服务建设\"}]}}");
            }
        });
        Job job = new JobSourceRegistry(List.of(source)).search(new JobQuery().keyword("Java")).jobs().get(0);
        assertThat(job.searchKeywords()).containsExactly("Java");
        assertThat(job.skills()).doesNotContain("Java");
        assertThat(new com.shijie.resumehelper.job.match.MatchScorer().score(job,
                com.shijie.resumehelper.common.profile.UserProfileSnapshot.builder().skills(List.of("Java")).build()).matchedSkills())
                .doesNotContain("Java");
    }

    @Test void liveSourcesWinAndFailedSourcesDoNotRemoveTheLocalFallback() {
        Job local = Job.builder().title("Java 开发").company("演示公司").source("演示", "").build();
        Job real = Job.builder().title("Java 开发").company("真实公司").source("官网", "https://example.com/job/1").build();
        JobSource live = new JobSource() {
            public String name() { return "官网"; }
            public List<Job> search(JobQuery q) { return List.of(real); }
        };
        JobSource fallback = new JobSource() {
            public String name() { return "演示"; }
            public String kind() { return "mock"; }
            public List<Job> search(JobQuery q) { return List.of(local); }
        };
        var registry = new JobSourceRegistry(List.of(fallback, live));
        assertThat(registry.search(new JobQuery()).jobs()).containsExactly(real);
        JobSource failed = new JobSource() {
            public String name() { return "断线官网"; }
            public List<Job> search(JobQuery q) { throw new JobSourceException(name(), "网络不可用"); }
        };
        var result = new JobSourceRegistry(List.of(fallback, failed)).search(new JobQuery());
        assertThat(result.jobs()).containsExactly(local);
        assertThat(result.reports()).anyMatch(r -> !r.ok() && r.message().contains("网络不可用"));
    }

    @Test void webSearchRejectsUnsafeLinksAndNonJobArticles() throws Exception {
        var source = new TavilyJobSource("");
        assertThat(source.enabled()).isFalse();
        var rows = json.readTree("""
                [{"url":"javascript:alert(1)","title":"Java 招聘","content":"岗位职责"},
                {"url":"https://example.com/guide","title":"求职攻略","content":"岗位职责 任职要求"}]
                """);
        assertThat(source.parse(rows, "上海")).isEmpty();
    }
}
