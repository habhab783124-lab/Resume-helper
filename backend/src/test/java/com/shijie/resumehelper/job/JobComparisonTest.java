package com.shijie.resumehelper.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.profile.UserProfileSnapshot;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.job.api.JobComparisonController;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.service.JobComparisonService;
import com.shijie.resumehelper.job.store.*;
import com.shijie.resumehelper.material.store.ProfileStore;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class JobComparisonTest {
    @TempDir Path root;
    JobStore jobs;ImportedJobStore imports;ProfileStore profiles;JobComparisonService service;
    @BeforeEach void setup(){
        jobs=new InMemoryJobStore();imports=new ImportedJobStore(root.toString(),new ObjectMapper().findAndRegisterModules());
        profiles=mock(ProfileStore.class);service=new JobComparisonService(jobs,imports,profiles);
        jobs.put(job("a","上海","10K"));jobs.put(job("b","北京","30K"));
    }
    Job job(String id,String city,String salary){return Job.builder().id(id).title("Java开发").company("示例公司")
            .city(city).salary(salary).skills(List.of("Java","Redis")).description("使用 Java 和 Redis 开发服务")
            .education("本科").experience("应届生").build();}
    UserProfileSnapshot profile(){return UserProfileSnapshot.builder().userId("alice").targetTitle("Java开发")
            .targetCities(List.of("上海")).expectedSalary("20K").skills(List.of("Java")).education("本科").build();}
    JobComparisonService.Comparison compare(String preference){return service.compare("alice",new JobComparisonService.Request(List.of("a","b"),preference));}
    JobComparisonService.Item item(JobComparisonService.Comparison c,String id){return c.items().stream().filter(i->i.job().id().equals(id)).findFirst().orElseThrow();}
    @Test void noConfirmedProfileShowsConditionsWithoutPersonalScores(){
        var result=compare("balanced");assertFalse(result.profileAvailable());
        assertEquals(2,result.items().size());assertTrue(result.items().stream().allMatch(i->i.score()==null));
        assertTrue(result.items().stream().allMatch(i->i.dimensions().stream().allMatch(d->d.points()==null)));
        assertTrue(result.items().stream().allMatch(i->i.unrepresentedSkills().isEmpty()));
    }
    @Test void anEmptyStoredProfileIsNotTreatedAsConfirmedPersonalInformation(){
        when(profiles.find("alice")).thenReturn(Optional.of(UserProfileSnapshot.builder().build()));
        var result=compare("balanced");assertFalse(result.profileAvailable());
        assertTrue(result.items().stream().allMatch(i->i.score()==null&&i.unrepresentedSkills().isEmpty()));
        when(profiles.find("alice")).thenReturn(Optional.of(UserProfileSnapshot.builder().targetCities(List.of("上海")).build()));
        assertTrue(compare("city").profileAvailable());
        assertEquals("a",compare("city").items().get(0).job().id());
    }
    @Test void changingPreferencesChangesRankingWithExplainableWeights(){
        when(profiles.find("alice")).thenReturn(Optional.of(profile()));
        assertEquals("a",compare("city").items().get(0).job().id());
        assertEquals("b",compare("salary").items().get(0).job().id());
        for(String p:List.of("balanced","city","salary","skills")){
            var result=compare(p);assertEquals(100,result.items().get(0).dimensions().stream().mapToInt(JobComparisonService.Dimension::weight).sum());
            assertTrue(result.items().stream().allMatch(i->i.score()>=0&&i.score()<=100));
        }
    }
    @Test void absentSalaryAndLocationRemainUnknownAndNeverScoreZeroForConflict(){
        when(profiles.find("alice")).thenReturn(Optional.of(profile()));
        jobs.put(job("a","","面议"));var entry=item(compare("balanced"),"a");
        assertNull(entry.salaryMin());assertNull(entry.salaryMax());
        assertTrue(entry.dimensions().stream().filter(d->Set.of("城市","薪资").contains(d.label())).allMatch(d->d.points()==null));
        assertTrue(entry.knownWeight()<100);
        assertEquals((int)Math.round(entry.dimensions().stream().mapToDouble(d->d.points()==null?d.weight()*.5:d.points()).sum()),entry.score());
    }
    @Test void targetTitleDoesNotBecomeEvidenceAndMissingSkillsAreOnlyUnrepresented(){
        when(profiles.find("alice")).thenReturn(Optional.of(UserProfileSnapshot.builder().targetTitle("Java开发").build()));
        var titleOnly=item(compare("balanced"),"a");assertTrue(titleOnly.evidencedSkills().isEmpty());
        assertTrue(titleOnly.unrepresentedSkills().contains("Java"));assertNull(titleOnly.dimensions().get(0).points());
        when(profiles.find("alice")).thenReturn(Optional.of(profile()));
        var entry=item(compare("balanced"),"a");assertEquals(List.of("Java"),entry.evidencedSkills());
        assertTrue(entry.unrepresentedSkills().contains("Redis"));
        assertTrue(entry.dimensions().get(0).note().contains("未体现不代表不会"));
    }
    @Test void privateImportedSnapshotsAreScopedAndNeverReadFromPublicStore(){
        String id="imported-"+"c".repeat(64);var secret=job(id,"上海","30K");imports.save("alice",secret);jobs.put(secret);
        var request=new JobComparisonService.Request(List.of(id,"b"),"balanced");
        assertEquals(2,service.compare("alice",request).items().size());
        var other=service.compare("bob",request);assertEquals(List.of(id),other.unavailableIds());
        assertEquals(List.of("b"),other.items().stream().map(i->i.job().id()).toList());
    }
    @Test void invalidAndDuplicateSelectionsAreRejected(){
        for(List<String> ids:List.of(List.<String>of(),List.of("a"),List.of("a","a"),List.of("a","b","c","d","e"),List.of("a"," ")))
            assertThrows(IllegalArgumentException.class,()->service.compare("alice",new JobComparisonService.Request(ids,"balanced")));
        assertThrows(IllegalArgumentException.class,()->compare("other"));
        assertThrows(IllegalArgumentException.class,()->service.compare("alice",null));
    }
    @Test void controllerUsesAuthenticatedOwnerAndRetainsSelectionOrderForTies(){
        var request=new MockHttpServletRequest();request.addHeader(UserContext.HEADER,"bob");
        request.setAttribute(UserContext.AUTHENTICATED_USER,"alice");
        when(profiles.find("alice")).thenReturn(Optional.of(profile()));
        var result=new JobComparisonController(service).compare(new JobComparisonService.Request(List.of("b","a"),"skills"),request).data();
        verify(profiles).find("alice");assertTrue(result.profileAvailable());
        when(profiles.find("alice")).thenReturn(Optional.empty());assertEquals(List.of("b","a"),service.compare("alice",new JobComparisonService.Request(List.of("b","a"),null)).items().stream().map(i->i.job().id()).toList());
    }
    @Test void dailySalaryUsesExistingNormalizedMonthlyUnitsAndSnapshotIncludesFullJd(){
        jobs.put(job("a","上海","300元/天").toBuilder().description("原文".repeat(200)).build());
        var entry=item(compare("balanced"),"a");assertEquals(7,entry.salaryMax());
        assertEquals(400,entry.job().description().length());assertTrue(entry.notes().get(0).contains("21.75"));
    }
}
