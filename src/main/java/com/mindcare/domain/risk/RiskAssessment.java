package com.mindcare.domain.risk;

import java.util.List;

public record RiskAssessment(RiskLevel level, List<String> ruleHits) {
    public boolean requiresAlert() { return level == RiskLevel.HIGH || level == RiskLevel.CRITICAL; }
}
