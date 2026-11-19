package com.mindcare.domain.report.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 전문가 리포트 화면(E06)용. content·client_summary는 JSON 객체 그대로 내려준다 (화면이 객체로 읽음).
 * sources_meta: 회기별 상담 시간(분).
 */
public record ExpertReportResponse(UUID id, String scope, String status, short version, Map<String, Object> content,
        Map<String, Object> clientSummary, String riskLevel, Instant readyAt, List<ReportResponse.Source> sources,
        List<SourceMeta> sourcesMeta) {
    public record SourceMeta(int sessionNo, int durationMin) { }
}
