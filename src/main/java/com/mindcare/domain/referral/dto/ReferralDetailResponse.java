package com.mindcare.domain.referral.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 연계 화면(C12) 한 번에 그리는 데이터.
 * eligible_sessions: 포함할 수 있는 내 완료 코칭 회기, included_session_ids: 지금 연계 리포트에 포함된 회기,
 * candidates: 고를 수 있는 전문가, report: 연계 리포트(scope=referral).
 */
public record ReferralDetailResponse(Referral referral, List<EligibleSession> eligibleSessions, List<UUID> includedSessionIds,
        List<Candidate> candidates, ReportRef report) {
    public record Referral(UUID id, UUID sourceSessionId, UUID expertId, String status, Instant includedLockedAt, Instant createdAt) { }
    public record EligibleSession(UUID sessionId, int sessionNo, Instant date, String providerDisplay, String reportStatus) { }
    public record Candidate(UUID providerId, String displayName, String track, Instant earliestSlot, Integer price) { }
    public record ReportRef(UUID id, String status, short version) { }
}
