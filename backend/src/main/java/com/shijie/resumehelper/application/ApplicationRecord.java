package com.shijie.resumehelper.application;

import com.shijie.resumehelper.job.domain.Job;
import com.shijie.resumehelper.resume.domain.ResumeVersion;
import java.time.Instant;
import java.util.List;

/** A private application freezes its JD and chosen resume independently of the live sources. */
public record ApplicationRecord(String id, String ownerId, Job jobSnapshot,
        String resumeVersionId, ResumeVersion resumeSnapshot, Status status,
        Instant appliedAt, Instant nextActionAt, Instant createdAt, Instant updatedAt,
        long version, List<Event> events) {
    public ApplicationRecord { events=List.copyOf(events); }
    public enum Status { PREPARING, APPLIED, TEST, INTERVIEW, OFFER, CLOSED }
    public record Event(String id, String type, Status fromStatus, Status toStatus,
            String note, Instant occurredAt, String interviewSessionId) {}
}
