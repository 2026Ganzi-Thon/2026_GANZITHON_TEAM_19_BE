package com.mindcare.domain.report.dto;
import java.util.List;
import java.time.Instant;
import java.util.UUID;
public record ReportResponse(UUID id, String scope, String status, short version, String content, String clientSummary, String riskLevel, Instant readyAt,
        List<Source> sources) {
    /** 리포트가 다루는 코칭 회기. date는 회기 시작 시각, session_no는 1부터. */
    public record Source(UUID sessionId, int sessionNo, Instant date, String providerDisplay) {}
}
