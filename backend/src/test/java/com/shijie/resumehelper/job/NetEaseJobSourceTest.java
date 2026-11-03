package com.shijie.resumehelper.job;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.domain.JobQuery;
import com.shijie.resumehelper.job.source.JobSource;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.source.NetEaseJobSource;
import com.shijie.resumehelper.job.source.PublicJobJsonClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class NetEaseJobSourceTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void officialFactsKeepRequirementsLocationsAndSafeSourceLinks() throws Exception {
        var jobs = new NetEaseJobSource().parse(json.readTree("""
                [{"id":77492,"name":"Java开发工程师","workType":"0","reqEducationName":"本科",
                  "reqWorkYearsName":"3-5年","workPlaceNameList":["杭州市","北京市"],
                  "description":"开发后端服务","requirement":"熟悉 Java、Spring Boot 和 Redis","productName":"网易云音乐"},
                 {"id":"invalid/path","name":"无效岗位"},{"id":2,"name":""}]
                """));
        assertThat(jobs).hasSize(1);
        Job job = jobs.get(0);
        assertThat(job.id()).isEqualTo("netease-77492");
        assertThat(job.sourceUrl()).isEqualTo("https://hr.163.com/job-detail.html?id=77492");
        assertThat(job.city()).isEqualTo("杭州 / 北京");
        assertThat(job.cityMatches(List.of("北京"))).isTrue();
        assertThat(job.minYears()).isEqualTo(3);
        assertThat(job.education()).isEqualTo("本科");
        assertThat(job.salary()).isEqualTo("面议");
        assertThat(job.skills()).contains("Java", "Spring Boot", "Redis");
        assertThat(job.description()).contains("任职要求", "网易云音乐");
        assertThat(job.jobType()).isEqualTo("全职");
    }

    @Test void internshipsAreIdentifiedWithoutInventingEducationOrSalary() throws Exception {
        Job job = new NetEaseJobSource().parse(json.readTree("""
                [{"id":1,"name":"内容运营","workType":"1","reqWorkYearsName":"不限"}]
                """)).get(0);
        assertThat(job.jobType()).isEqualTo("实习");
        assertThat(job.campus()).isTrue();
        assertThat(job.education()).isEqualTo("未披露");
        assertThat(job.salary()).isEqualTo("面议");
        assertThat(job.city()).isEmpty();
    }

    @Test void paginationReachesLaterMatchesAndCachesOfficialRequestsWithoutProfileData() {
        var pages = new ArrayList<Integer>();
        var source = new NetEaseJobSource(new PublicJobJsonClient() {
            @Override public JsonNode post(URI uri, Object body) {
                assertThat(uri.toString()).isEqualTo("https://hr.163.com/api/hr163/position/queryPage");
                var input = (Map<?, ?>) body;
                assertThat(input.keySet().stream().map(Object::toString).toList())
                        .containsExactlyInAnyOrder("currentPage", "pageSize", "keyword");
                assertThat(input.get("keyword")).isEqualTo("C++");
                int page = (int) input.get("currentPage");
                pages.add(page);
                var root = json.createObjectNode(); root.put("code", 200);
                var data = root.putObject("data"); data.put("total", 601);
                var posts = data.putArray("list");
                var post = posts.addObject(); post.put("id", page); post.put("name", "C++ 开发 " + page);
                post.put("reqWorkYearsName", page < 7 ? "5-10年" : "1-3年");
                post.putArray("workPlaceNameList").add(page < 7 ? "杭州市" : "北京市");
                return root;
            }
        });
        var query = new JobQuery().keyword("C++").city("北京").strictCity(true).maxMinYears(1).limit(500);
        var result = new JobSourceRegistry(List.of(source)).search(query);
        assertThat(pages).containsExactly(1, 2, 3, 4, 5, 6, 7);
        assertThat(result.jobs()).extracting(Job::id).containsExactly("netease-7");
        source.search(query);
        assertThat(pages).hasSize(7);
    }

    @Test void offlineSourceReportsFailureWhileOtherSourcesStillReturnJobs() {
        var source = new NetEaseJobSource(new PublicJobJsonClient() {
            @Override public JsonNode post(URI uri, Object body) { throw new IllegalStateException("network unavailable"); }
        });
        JobSource other = new JobSource() {
            public String name() { return "另一官网"; }
            public List<Job> search(JobQuery query) { return List.of(Job.builder().title("Java开发").company("公司")
                    .source(name(), "https://example.com/job/1").build()); }
        };
        var result = new JobSourceRegistry(List.of(source, other)).search(new JobQuery().keyword("Java"));
        assertThat(result.jobs()).hasSize(1);
        assertThat(result.reports()).anyMatch(report -> report.name().equals(source.name()) && !report.ok());
    }

    @Test void keywordSearchDoesNotFetchUnrelatedPagesWhenThereAreNoMatches() {
        var calls = new AtomicInteger();
        var source = new NetEaseJobSource(new PublicJobJsonClient() {
            @Override public JsonNode post(URI uri, Object body) throws Exception {
                calls.incrementAndGet();
                return json.readTree("{\"code\":200,\"data\":{\"total\":0,\"list\":[]}}");
            }
        });
        assertThat(new JobSourceRegistry(List.of(source)).search(new JobQuery().keyword("不存在的岗位")).jobs()).isEmpty();
        assertThat(calls).hasValue(1);
    }
}
