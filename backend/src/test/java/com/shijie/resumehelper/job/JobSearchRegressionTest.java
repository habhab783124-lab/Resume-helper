package com.shijie.resumehelper.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.job.agent.*;
import com.shijie.resumehelper.job.domain.*;
import com.shijie.resumehelper.job.source.*;
import com.shijie.resumehelper.job.store.ImportedJobStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.*;

class JobSearchRegressionTest {
    private Job job(String title, String city, int years) {
        return Job.builder().title(title).company("公司").city(city).minYears(years).source("测试源", "").build();
    }
    private JobSource source(List<Job> jobs) {
        return new JobSource() {
            public String name() { return "测试源"; }
            public List<Job> search(JobQuery query) { return jobs; }
        };
    }
    @Test void explicitSearchNeverFallsBackToUnrelatedJobs() {
        var registry = new JobSourceRegistry(List.of(source(List.of(job("Java", "上海", 3)))));
        assertThat(registry.search(new JobQuery().keyword("不存在的职位")).jobs()).isEmpty();
        assertThat(registry.search(new JobQuery().keyword("Java")).jobs()).hasSize(1);
    }
    @Test void keywordMatchesCompaniesAndMultipleTermsWithoutConfusingCPlusPlusAndCSharp() {
        var query = new JobQuery();
        Job cpp = job("C++ 后端", "上海", 3);
        assertThat(query.matchesKeyword(cpp, "公司 上海 C++")).isTrue();
        assertThat(query.matchesKeyword(cpp, "C#")).isFalse();
        assertThat(query.matchesKeyword(cpp, "Java 上海")).isFalse();
    }
    @Test void cityAndExperienceFiltersRemainHardConstraintsIncludingUnknownCities() {
        var registry = new JobSourceRegistry(List.of(source(List.of(
                job("Java junior", "上海", 1), job("Java senior", "上海", 5), job("Java 北京", "北京", 1), job("Java unknown", "", 0)))));
        var query = new JobQuery().keyword("Java").city("上海").strictCity(true).maxMinYears(1);
        assertThat(registry.search(query).jobs()).extracting(Job::title).containsExactly("Java junior");
        assertThat(registry.search(new JobQuery()).jobs()).hasSize(4);
    }
    @Test void largerResultLimitsArePreservedThroughRanking() {
        var jobs = IntStream.range(0, 650).mapToObj(i -> job("Java 工程师 " + i, "上海", 3)).toList();
        var agent = new JobSearchAgent(new JobSourceRegistry(List.of(source(jobs))));
        var result = agent.search(UserProfileSnapshot.builder().targetTitle("Java").build(),
                new JobQuery().keyword("Java").limit(500), AgentOptions.builder().limit(500).explainTopN(0).build(), null);
        assertThat(result.matches()).hasSize(500);
        assertThat(new JobQuery().limit(10000).limit()).isEqualTo(500);
        assertThat(AgentOptions.builder().limit(10000).build().limit()).isEqualTo(500);
    }

    @Test void ranksTheCandidatePoolBeforeApplyingTheDisplayLimit() {
        var jobs = new java.util.ArrayList<>(IntStream.range(0, 70)
                .mapToObj(i -> job("其他工程师 " + i, "北京", 5)).toList());
        jobs.add(job("Java 后端开发", "上海", 1).toBuilder().skills(List.of("Java", "Spring Boot")).build());
        var agent = new JobSearchAgent(new JobSourceRegistry(List.of(source(jobs))));
        var profile = UserProfileSnapshot.builder().targetTitle("Java 后端开发").skills(List.of("Java", "Spring Boot"))
                .targetCities(List.of("上海")).build();
        var query = new JobQuery().limit(12);
        var result = agent.search(profile, query, AgentOptions.builder().limit(12).explainTopN(0).build(), null);
        assertThat(result.matches()).hasSize(12);
        assertThat(result.matches().get(0).job().title()).isEqualTo("Java 后端开发");
        assertThat(result.candidateCount()).isEqualTo(71);
        assertThat(query.limit()).isEqualTo(12);
        assertThat(com.shijie.resumehelper.job.api.dto.JobViews.JobList.of(result, id -> false).candidateCount()).isEqualTo(71);
    }

    @Test void defaultSearchReturnsMoreThanTwelveAndDoesNotExcludeExperiencedJobs() {
        var jobs = IntStream.range(0, 80).mapToObj(i -> job("Java 工程师 " + i, "上海", 3)).toList();
        var registry = new JobSourceRegistry(List.of(source(jobs)));
        var service = new com.shijie.resumehelper.job.service.JobService(registry, new JobSearchAgent(registry),
                new com.shijie.resumehelper.job.store.InMemoryJobStore(), new JobImporter(),
                new com.shijie.resumehelper.job.match.MatchScorer(), new com.shijie.resumehelper.job.config.JobModuleProperties());
        var result = service.search(null, new com.shijie.resumehelper.job.api.dto.JobRequests.Search(), null);
        assertThat(result.matches()).hasSize(60);
        assertThat(result.candidateCount()).isEqualTo(80);
        assertThat(com.shijie.resumehelper.job.api.dto.JobViews.JobList.of(result, id -> false).jobs())
                .allMatch(card -> card.score() == null);
    }

    @Test void explicitAgentSearchDoesNotRepeatTheSameNetworkQuery() {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var registry = new JobSourceRegistry(List.of(new JobSource() {
            public String name() { return "测试源"; }
            public List<Job> search(JobQuery query) { calls.incrementAndGet(); return List.of(job("Java", "上海", 3)); }
        }));
        var result = new JobSearchAgent(registry).run(UserProfileSnapshot.builder().build(),
                AgentOptions.builder().explainTopN(0).build(), null, "Java");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(result.matches()).hasSize(1);
        assertThat(com.shijie.resumehelper.job.api.dto.JobViews.AgentRunView.of(result, id -> false).jobs().get(0).score()).isNull();
    }

    @Test void cappedResultsKeepMultipleOfficialSourcesRepresented() {
        var firstJobs = IntStream.range(0, 600).mapToObj(i -> job("Java " + i, "上海", 3)
                .toBuilder().source("官网一", "https://example.com/a/" + i).build()).toList();
        var secondJobs = IntStream.range(0, 600).mapToObj(i -> job("Java " + i, "北京", 1)
                .toBuilder().source("官网二", "https://example.com/b/" + i).build()).toList();
        JobSource first = new JobSource() {
            public String name() { return "官网一"; }
            public List<Job> search(JobQuery query) { return firstJobs; }
        };
        JobSource second = new JobSource() {
            public String name() { return "官网二"; }
            public List<Job> search(JobQuery query) { return secondJobs; }
        };
        var result = new JobSourceRegistry(List.of(first, second)).search(new JobQuery().limit(500));
        assertThat(result.jobs()).hasSize(500);
        assertThat(result.jobs().stream().filter(job -> job.sourceName().equals("官网一"))).hasSize(250);
        assertThat(result.jobs().stream().filter(job -> job.sourceName().equals("官网二"))).hasSize(250);
        assertThat(result.dedupedCount()).isEqualTo(1200);
    }
    @Test void agentHonorsUserKeywordEvenIfTheModelRequestsSomethingElse() {
        var agent = new JobSearchAgent(new JobSourceRegistry(List.of(source(List.of(job("Java", "上海", 1), job("Python", "上海", 1))))));
        var result = agent.run(UserProfileSnapshot.builder().targetTitle("Java").build(),
                AgentOptions.builder().explainTopN(0).build(), (system, prompt) -> "ACTION: search_jobs\nARGS:\nkeyword=Java", "Python");
        assertThat(result.matches()).extracting(match -> match.job().title()).containsExactly("Python");
    }
    @Test void pastedJobsSurviveStoreRecreationAndStayPrivate(@TempDir Path directory) {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var store = new ImportedJobStore(directory.toString(), mapper);
        String id = "imported-" + "a".repeat(64);
        store.save("owner", job("Java", "上海", 1).toBuilder().id(id).build());
        var restarted = new ImportedJobStore(directory.toString(), mapper);
        assertThat(restarted.list("owner")).hasSize(1);
        assertThat(restarted.find("owner", id)).isPresent();
        assertThat(restarted.find("other", id)).isEmpty();
        assertThat(restarted.list("other")).isEmpty();
        assertThat(restarted.find("owner", "../../profile")).isEmpty();
    }

    @Test void privateImportsNeverMergeTheirContentsIntoSharedPublicJobCache() {
        var publicJob = job("Java", "上海", 1).toBuilder().description("公开 JD").build();
        var privateJob = publicJob.toBuilder().id("imported-" + "b".repeat(64)).description("PRIVATE 用户补充的私密岗位原文").build();
        var registry = new JobSourceRegistry(List.of(source(List.of(publicJob))));
        var store = new com.shijie.resumehelper.job.store.InMemoryJobStore();
        var service = new com.shijie.resumehelper.job.service.JobService(registry, new JobSearchAgent(registry), store,
                new JobImporter(), new com.shijie.resumehelper.job.match.MatchScorer(), new com.shijie.resumehelper.job.config.JobModuleProperties());
        var request = new com.shijie.resumehelper.job.api.dto.JobRequests.Search();
        var result = service.search(null, request, null, List.of(privateJob));
        assertThat(result.matches()).hasSize(2);
        assertThat(store.find(publicJob.id()).orElseThrow().description()).isEqualTo("公开 JD");
        assertThat(store.find(privateJob.id())).isEmpty();
    }

    @Test void privateImportsAreReportedSeparatelyAndNeverMislabeledAsBuiltInDemonstrations() {
        var agent = new JobSearchAgent(new JobSourceRegistry(List.of()));
        var imported = job("Java", "上海", 1).toBuilder().id("imported-" + "c".repeat(64)).build();
        var result = agent.search(UserProfileSnapshot.builder().build(), new JobQuery(),
                AgentOptions.builder().explainTopN(0).build(), null, List.of(imported));
        assertThat(result.sourceReports()).anyMatch(r -> r.kind().equals("import") && r.ok() && r.count() == 1);
        assertThat(result.notes()).anyMatch(note -> note.contains("本人导入"));
        assertThat(result.notes()).noneMatch(note -> note.contains("演示数据"));
    }
}
