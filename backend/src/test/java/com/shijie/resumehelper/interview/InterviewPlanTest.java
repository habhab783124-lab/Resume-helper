package com.shijie.resumehelper.interview;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class InterviewPlanTest {
    @Test
    void legacyPlanDoesNotInventExperienceModeChoicesOrFollowUpBudget() {
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");

        var plan = InterviewPlan.legacyV3(source, "后端工程师");

        assertEquals("legacy-v3", plan.policyVersion());
        assertNull(plan.level());
        assertNull(plan.careerStage());
        assertNull(plan.followUpBudget());
        assertEquals(12, plan.mainCount());
        assertEquals(13, plan.plannedCount());
    }

    @Test
    void planFreezesCopiesOfCoverageAndPracticeTargets() {
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.RESUME_VERSION, "v1");
        var coverage = new ArrayList<>(java.util.List.of(new InterviewPlan.Coverage("project", "项目实践")));
        var targets = new ArrayList<>(java.util.List.of(new InterviewPlan.PracticeTarget("gap-1", "量化结果", "补充真实指标", java.util.List.of("q1"))));

        var plan = new InterviewPlan("experience-v1", InterviewPlan.Mode.QUICK, 3, 3, 2, 1,
                InterviewPlan.Level.FOUNDATION, InterviewPlan.CareerStage.INTERNSHIP, "产品助理", "",
                new InterviewPlan.Specialty(InterviewPlan.SpecialtyKind.NONE, "", ""), coverage,
                source, "v1", "fingerprint", targets);
        coverage.clear();
        targets.clear();

        assertEquals(1, plan.coverage().size());
        assertEquals(1, plan.practiceTargets().size());
        assertThrows(UnsupportedOperationException.class, () -> plan.coverage().clear());
    }
}
