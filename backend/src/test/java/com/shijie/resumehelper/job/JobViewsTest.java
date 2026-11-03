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
    @Test
    void cardsExposeNormalizedSalaryWithoutChangingTheOriginalSalaryText() {
        Job job = Job.builder().title("工程师").salary("300元/天").build();
        for (var card : java.util.List.of(JobViews.JobCard.of(job, false), JobViews.JobCard.ofDetailed(job, false))) {
            assertEquals("300元/天", card.salary());
            assertEquals(7, card.salaryMin());
            assertEquals(7, card.salaryMax());
        }
    }
}
