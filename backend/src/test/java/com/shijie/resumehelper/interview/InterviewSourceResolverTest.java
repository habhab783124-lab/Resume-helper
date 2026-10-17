package com.shijie.resumehelper.interview;

import com.shijie.resumehelper.material.service.MaterialService;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialDetailView;
import com.shijie.resumehelper.material.api.dto.MaterialViews.MaterialView;
import com.shijie.resumehelper.resume.domain.ResumeDraft;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import com.shijie.resumehelper.resume.service.ResumeService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.HashMap;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.*;

class InterviewSourceResolverTest {
    private final Map<String, MaterialDetailView> materialDetails = new HashMap<>();
    private final Map<String, ResumeVersion> versions = new HashMap<>();
    private final MaterialService materials = new MaterialService(null, null, null, null) {
        @Override public Optional<MaterialDetailView> find(String owner, String id) {
            return Optional.ofNullable(materialDetails.get(owner + ":" + id));
        }
    };
    private final ResumeService resumes = new ResumeService(null, null) {
        @Override public Optional<ResumeVersion> findVersion(String owner, String id) {
            return Optional.ofNullable(versions.get(owner + ":" + id));
        }
    };
    private final InterviewSourceResolver resolver = new InterviewSourceResolver(materials, resumes);

    @Test
    void resolvesMaterialTextWithoutChangingItAndBuildsStableFingerprint() {
        materialDetails.put("alice:m1", material("m1", "Bo Resume.txt", "教育\n学校 A\n经历\n完成项目"));
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");

        var first = resolver.resolve("alice", source);
        var second = resolver.resolve("alice", source);

        assertEquals(source, first.source());
        assertEquals("Bo Resume.txt", first.sourceLabel());
        assertEquals("教育\n学校 A\n经历\n完成项目", first.resumeText());
        assertEquals(first.sourceFingerprint(), second.sourceFingerprint());
        materialDetails.put("alice:m1", material("m1", "Bo Resume.txt", "更新后的简历"));
        assertNotEquals(first.sourceFingerprint(), resolver.resolve("alice", source).sourceFingerprint());
    }

    @Test
    void convertsResumeVersionDeterministicallyAndOmitsContacts() {
        var draft = new ResumeDraft("Java 工程师", "可靠后端", List.of("private@example.com"),
                List.of(new ResumeDraft.Entry("大学", "2020-2024", List.of("计算机"))),
                List.of(new ResumeDraft.Entry("公司", "2024", List.of("开发接口", "优化服务"))),
                List.of(new ResumeDraft.Entry("项目", "", List.of("搭建平台"))),
                List.of("Java", "SQL"), List.of("分布式"));
        versions.put("alice:v1", new ResumeVersion("v1", "alice", "后端版", "Java", "JD", draft, Instant.EPOCH));

        var resolved = resolver.resolve("alice",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.RESUME_VERSION, "v1"));

        assertEquals("后端版", resolved.sourceLabel());
        assertTrue(resolved.resumeText().contains("教育经历\n大学（2020-2024）\n- 计算机"));
        assertTrue(resolved.resumeText().contains("工作经历\n公司（2024）\n- 开发接口\n- 优化服务"));
        assertTrue(resolved.resumeText().contains("项目经历\n项目\n- 搭建平台"));
        assertFalse(resolved.resumeText().contains("private@example.com"));
        assertTrue(resolved.resumeText().contains("Java 工程师"));
    }

    @Test
    void rejectsMissingSourcesEmptyTextAndOversizedText() {
        materialDetails.put("alice:empty", material("empty", "empty.txt", " \n "));
        materialDetails.put("alice:long", material("long", "long.txt", "x".repeat(12001)));

        assertThrows(InterviewException.class, () -> resolver.resolve("alice",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "missing")));
        assertThrows(InterviewException.class, () -> resolver.resolve("alice",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "empty")));
        assertThrows(InterviewException.class, () -> resolver.resolve("alice",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "long")));
        assertThrows(InterviewException.class, () -> resolver.resolve("bob",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "missing")));
    }

    @Test
    void missingResumeVersionIsNotFound() {
        assertThrows(InterviewException.class, () -> resolver.resolve("alice",
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.RESUME_VERSION, "missing")));
    }

    @Test
    void startPersistsTheResolvedTextAndFingerprintAndRejectsChangedSourceReplay() {
        var source = new InterviewSourceResolver.ResolvedSource(
                new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.RESUME_VERSION, "v1"),
                "后端版", "稳定的简历快照", "fingerprint-1");
        var store = new CapturingStore();
        var interviews = new InterviewService(new InterviewAgent(), store, Clock.systemUTC());

        var session = interviews.startAsync("alice", source, "Java 后端", "start-1");
        var snapshot = store.snapshot;

        assertEquals(source.source(), session.plan().source());
        assertEquals("稳定的简历快照", snapshot.resumeText());
        assertEquals(source.source(), snapshot.plan().source());
        assertEquals("后端版", snapshot.plan().sourceLabel());
        assertEquals("fingerprint-1", snapshot.plan().sourceFingerprint());
        var changed = new InterviewSourceResolver.ResolvedSource(source.source(), source.sourceLabel(),
                "源已变化", "fingerprint-2");
        assertEquals(409, assertThrows(InterviewException.class,
                () -> interviews.startAsync("alice", changed, "Java 后端", "start-1")).status());
        assertEquals(session.sessionId(), store.snapshot.sessionId());
    }

    @Test
    void previewEndpointReturnsTheSameOwnerResolvedSourceUsedByStart() {
        materialDetails.put("alice:m1", material("m1", "Resume.txt", "原简历正文"));
        var sources = new InterviewSourceResolver(materials, resumes);
        var controller = new InterviewController(new InterviewService(new InterviewAgent()), new InterviewAgent(), null,
                sources, null, null, null, null);
        var request = new MockHttpServletRequest();
        request.addHeader("X-User-Id", "alice");
        var source = new InterviewPlan.ResumeSource(InterviewPlan.SourceKind.MATERIAL, "m1");

        var response = controller.previewSource(new InterviewController.SourcePreviewRequest(source), request);

        assertTrue(response.success());
        assertEquals("原简历正文", response.data().resumeText());
        assertEquals("Resume.txt", response.data().sourceLabel());
        assertEquals(source, response.data().source());
    }

    private static MaterialDetailView material(String id, String fileName, String text) {
        var view = new MaterialView(id, fileName, "txt", "TXT", "1 KB", 1000, "TEXT_EXTRACTED",
                "已识别", "success", 0, 20, 0, "", Instant.EPOCH.toString(), true, false);
        return new MaterialDetailView(view, null, text);
    }

    private static final class CapturingStore implements InterviewStore {
        private InterviewSnapshot snapshot;
        @Override public void save(InterviewSnapshot value) { snapshot = value; }
        @Override public Optional<InterviewSnapshot> find(String owner, String sessionId) {
            return snapshot != null && snapshot.owner().equals(owner) && snapshot.sessionId().equals(sessionId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
        @Override public Optional<InterviewSnapshot> findByStartRequestId(String owner, String requestId) {
            return snapshot != null && snapshot.owner().equals(owner) && snapshot.startRequestId().equals(requestId)
                    ? Optional.of(snapshot) : Optional.empty();
        }
        @Override public int deleteExpired(Instant now) { return 0; }
    }
}
