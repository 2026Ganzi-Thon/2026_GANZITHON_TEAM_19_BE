package com.mindcare.domain.session.dto;

import com.mindcare.domain.report.entity.Report;import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.entity.SessionStatus;
import java.time.Instant;
import java.util.UUID;
public record SessionResponse(UUID sessionId, String track, SessionStatus status, UUID clientId, UUID providerId,
                              Instant scheduledStart, Instant scheduledEnd, Short rating, ReportRef report) {
    public static SessionResponse from(Session s) { return from(s, null); }
    /** report는 이 세션의 세션 리포트(없으면 null). 프론트는 report.id로 리포트 화면에 들어간다. */
    public static SessionResponse from(Session s, Report report) { return new SessionResponse(s.getId(), s.getTrack(), s.getStatus(),
            s.getClient().getId(), s.getProvider().getId(), s.getScheduledStart(), s.getScheduledEnd(), s.getRating(),
            report == null ? null : new ReportRef(report.getId(), report.getStatus())); }
    public record ReportRef(UUID id, String status) { }
}
