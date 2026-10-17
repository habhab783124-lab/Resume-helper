package com.shijie.resumehelper.job;

import com.shijie.resumehelper.job.api.dto.JobViews;
import com.shijie.resumehelper.job.domain.Job;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JobViewsTest {
    @Test
    void detailedJobCardKeepsTheCompleteJobDescription() {
        String description = "职责与要求。".repeat(80);
        Job job = Job.builder().id("j1").title("工程师").company("公司")
                .description(description).build();

        assertEquals(description, JobViews.JobCard.ofDetailed(job, false).description());
        assertEquals(161, JobViews.JobCard.of(job, false).description().length());
    }
}
