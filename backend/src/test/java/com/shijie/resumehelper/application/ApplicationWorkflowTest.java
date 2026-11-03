package com.shijie.resumehelper.application;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.job.source.*;
import com.shijie.resumehelper.job.store.*;
import com.shijie.resumehelper.resume.service.ResumeService;
import com.shijie.resumehelper.interview.InterviewStore;
import com.shijie.resumehelper.common.util.UserContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.shijie.resumehelper.application.ApplicationRecord.Status.*;

class ApplicationWorkflowTest {
    @TempDir Path root;
    ObjectMapper mapper=new ObjectMapper().findAndRegisterModules();
    ApplicationService service; FileSystemApplicationStore records; ImportedJobStore imports;
    ResumeService resumes; InterviewStore interviews; JobService jobs;
    String jobId="imported-"+"a".repeat(64);
    @BeforeEach void setup(){
        records=new FileSystemApplicationStore(root.resolve("applications").toString(),mapper);
        imports=new ImportedJobStore(root.resolve("jobs").toString(),mapper);
        resumes=mock(ResumeService.class);interviews=mock(InterviewStore.class);
        jobs=new JobService(new JobSourceRegistry(List.of()),null,new InMemoryJobStore(),new JobImporter(),null,new JobModuleProperties());
        imports.save("alice",Job.builder().id(jobId).title("Java开发").description("熟悉 Redis").source("手动导入","").build());
        service=new ApplicationService(records,jobs,imports,resumes,interviews);
    }
    ApplicationRecord create(){return service.create("alice",new ApplicationService.Create(jobId,null));}
    ApplicationService.Change change(long version,ApplicationRecord.Status status){return new ApplicationService.Change(version,status,"已提交",null,Instant.parse("2020-01-01T00:00:00Z"),null);}
    @Test void duplicateCreatesAreIdempotentAndOtherOwnersCannotReadPrivateJob(){
        var first=create();assertEquals(first.id(),create().id());assertEquals(1,records.list("alice").size());
        assertEquals(404,assertThrows(ApplicationProblem.class,()->service.get("bob",first.id())).status());
        assertThrows(ApplicationProblem.class,()->service.create("bob",new ApplicationService.Create(jobId,null)));
    }
    @Test void privateImportsNeverFallBackToPublicSnapshots(){
        var publicJobs=mock(JobService.class);
        when(publicJobs.find(jobId)).thenReturn(imports.find("alice",jobId));
        var isolated=new ApplicationService(records,publicJobs,imports,resumes,interviews);
        assertThrows(ApplicationProblem.class,()->isolated.create("bob",new ApplicationService.Create(jobId,null)));
        verify(publicJobs,never()).find(anyString());
    }
    @Test void stateChangesKeepTimelineAndRejectStaleVersions(){
        var first=create();var updated=service.change("alice",first.id(),change(first.version(),APPLIED),false);
        assertEquals(APPLIED,updated.status());assertNotNull(updated.appliedAt());assertEquals(2,updated.events().size());
        assertEquals(409,assertThrows(ApplicationProblem.class,()->service.change("alice",first.id(),change(first.version(),TEST),false)).status());
        assertEquals(2,records.list("alice").get(0).events().size());
        assertEquals(1,service.list("alice",null,true,0,30).total());
    }
    @Test void invalidTransitionsNeverMutateTheRecord(){
        var first=create();assertThrows(IllegalArgumentException.class,()->service.change("alice",first.id(),change(1,OFFER),false));
        assertEquals(PREPARING,service.get("alice",first.id()).status());
    }
    @Test void frozenJobSurvivesSourceDeletionAndRepositoryRestart() throws Exception{
        var first=create();
        try(var files=Files.list(root.resolve("jobs").resolve("alice"))){for(Path file:files.toList())Files.delete(file);}
        var reopened=new ApplicationService(new FileSystemApplicationStore(root.resolve("applications").toString(),mapper),jobs,imports,resumes,interviews);
        assertEquals("熟悉 Redis",reopened.get("alice",first.id()).jobSnapshot().description());
        assertEquals(first.createdAt(),reopened.get("alice",first.id()).createdAt());
    }
    @Test void unknownResumeAndOtherUsersInterviewAreRejectedWithoutChanges(){
        assertThrows(ApplicationProblem.class,()->service.create("alice",new ApplicationService.Create(jobId,"other-resume")));
        assertTrue(records.list("alice").isEmpty());
        var first=create();
        var note=new ApplicationService.Change(1L,null,"面试",null,null,"other-session");
        assertThrows(ApplicationProblem.class,()->service.change("alice",first.id(),note,true));
        assertEquals(1,service.get("alice",first.id()).events().size());
    }
    @Test void ownedInterviewCanBeLinkedWithoutChangingProgressOrDeadline(){
        var first=create();
        var applied=service.change("alice",first.id(),change(1,APPLIED),false);
        when(interviews.find("alice","owned-session")).thenReturn(Optional.of(mock(com.shijie.resumehelper.interview.InterviewSnapshot.class)));
        var linked=service.change("alice",first.id(),new ApplicationService.Change(applied.version(),null,"面试复盘",null,null,"owned-session"),true);
        assertEquals(APPLIED,linked.status());
        assertEquals(applied.nextActionAt(),linked.nextActionAt());
        assertEquals("owned-session",linked.events().get(2).interviewSessionId());
        assertEquals("owned-session",new FileSystemApplicationStore(root.resolve("applications").toString(),mapper).list("alice").get(0).events().get(2).interviewSessionId());
    }
    @Test void controllerUsesAuthenticatedIdentityInsteadOfForgedHeader(){
        var first=create();var request=new MockHttpServletRequest();
        request.addHeader(UserContext.HEADER,"bob");request.setAttribute(UserContext.AUTHENTICATED_USER,"alice");
        assertEquals(first.id(),new ApplicationController(service).get(first.id(),request).data().id());
    }
    @Test void deleteRequiresCurrentVersionAndLeavesOtherOwnersUntouched(){
        var first=create();
        assertThrows(ApplicationProblem.class,()->service.delete("alice",first.id(),0));
        service.delete("alice",first.id(),1);
        assertTrue(records.list("alice").isEmpty());
    }
    @Test void damagedOwnerFileFailsClosedOnNextWrite() throws Exception{
        create();Path file=root.resolve("applications").resolve(com.shijie.resumehelper.common.util.AtomicJsonFiles.key("alice")+".json");
        Files.writeString(file,"{broken");
        assertThrows(java.io.UncheckedIOException.class,this::create);
        assertEquals("{broken",Files.readString(file));
    }
}
