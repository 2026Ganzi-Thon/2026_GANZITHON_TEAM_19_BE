package com.mindcare.domain.kpi.dto;
import java.time.Instant;
import java.util.Map;
public record KpiResponse(Instant from, Instant to, Map<String, Long> counts) {}
