package com.shijie.resumehelper.job;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.shijie.resumehelper.common.util.UserContext;
import com.shijie.resumehelper.job.api.JobController;
import com.shijie.resumehelper.job.api.dto.JobRequests;
import com.shijie.resumehelper.job.config.JobModuleProperties;
import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.job.service.JobService;
import com.shijie.resumehelper.job.source.JobImporter;
import com.shijie.resumehelper.job.source.JobSourceRegistry;
import com.shijie.resumehelper.job.store.FileSystemJobStore;
import com.shijie.resumehelper.job.store.ImportedJobStore;
import org.junit.jupiter.api.BeforeEach;
import com.shijie.resumehelper.material.store.ProfileStore;
import static org.mockito.Mockito.mock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class JobOwnershipTest {
    @TempDir Path root;
    ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    FileSystemJobStore publicJobs;
    ImportedJobStore imports;
    JobController controller;

    @BeforeEach void setup() {
        publicJobs = new FileSystemJobStore(root.toString(), mapper);
        imports = new ImportedJobStore(root.toString(), mapper);
        controller = controller(publicJobs, imports);
    }

    JobController controller(FileSystemJobStore store, ImportedJobStore privateJobs) {
        var service = new JobService(new JobSourceRegistry(List.of()), null, store,
                new JobImporter(), null, new JobModuleProperties());
        return new JobController(service, null, privateJobs, mock(ProfileStore.class));
    }

    MockHttpServletRequest request(String owner) {
        var request = new MockHttpServletRequest();
        request.setAttribute(UserContext.AUTHENTICATED_USER, owner);
        request.addHeader(UserContext.HEADER, "forged-owner");
        request.addParameter("userId", "forged-owner");
        return request;
    }

    JobRequests.Import pastedJob() {
        var body = new JobRequests.Import();
        body.setText("岗位：Java开发\n公司：示例公司（总部北京）\n工作地点：上海\n薪资：300元/天\n熟悉 Java 和 Redis");
        return body;
    }

    @Test void privateImportsAreStablePerOwnerAndNeverEnterThePublicStore() {
        var alice = controller.importJob(pastedJob(), request("alice")).data();
        assertEquals(alice.id(), controller.importJob(pastedJob(), request("alice")).data().id());
        assertNotEquals(alice.id(), controller.importJob(pastedJob(), request("bob")).data().id());
        assertTrue(publicJobs.find(alice.id()).isEmpty());
        assertTrue(controller.detail(alice.id(), null, request("alice")).success());
        assertFalse(controller.detail(alice.id(), null, request("bob")).success());
        assertFalse(controller.favorite(alice.id(), null, request("bob")).success());
    }

    @Test void favoritesUseAuthenticatedOwnerAndSurviveRepositoryRestart() {
        publicJobs.put(Job.builder().id("public-job").title("后端开发").build());
        var privateJob = controller.importJob(pastedJob(), request("alice")).data();
        controller.favorite("public-job", null, request("alice"));
        controller.favorite(privateJob.id(), null, request("alice"));
        assertEquals(2, controller.favorites(null, request("alice")).data().jobs().size());
        assertTrue(controller.favorites(null, request("bob")).data().jobs().isEmpty());
        assertFalse(publicJobs.isSaved("forged-owner", "public-job"));

        var reopened = controller(new FileSystemJobStore(root.toString(), mapper),
                new ImportedJobStore(root.toString(), mapper));
        assertEquals(2, reopened.favorites(null, request("alice")).data().jobs().size());
        assertTrue(reopened.detail(privateJob.id(), null, request("alice")).data().saved());
        reopened.unfavorite(privateJob.id(), null, request("bob"));
        assertTrue(reopened.detail(privateJob.id(), null, request("alice")).data().saved());
        reopened.unfavorite(privateJob.id(), null, request("alice"));
        assertEquals(1, reopened.favorites(null, request("alice")).data().jobs().size());
    }

    @Test void nonexistentJobsCannotBeFavorited() {
        assertFalse(controller.favorite("missing", null, request("alice")).success());
        assertTrue(controller.favorites(null, request("alice")).data().jobs().isEmpty());
    }
}
