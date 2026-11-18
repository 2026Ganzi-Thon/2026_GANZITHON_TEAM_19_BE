package com.mindcare.domain.report.dto;
import java.time.Instant;
import java.util.UUID;
public record ReportResponse(UUID id, String scope, String status, short version, String content, String clientSummary, String riskLevel, Instant readyAt) {}
