package com.mindcare.domain.intake.dto;

import com.mindcare.domain.intake.entity.Intake;
import com.mindcare.domain.intake.entity.IntakeCategory;
import com.mindcare.domain.intake.entity.RiskLevel;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record IntakeResponse(UUID intakeId, IntakeCategory category, Map<String, List<String>> aiTags,
        boolean aiFallback, Risk risk, List<Match> matches) {
    public static IntakeResponse created(Intake intake) {
        return new IntakeResponse(intake.getId(), intake.getCategory(), intake.getAiTags(),
                "fallback".equals(intake.getAiStatus()), new Risk(intake.getRiskLevel()), List.of());
    }
    public record Risk(RiskLevel level) { }
    public record Match(UUID providerId, String displayName, String track, List<String> specialties,
                        List<String> reasons, Instant earliestSlot, Integer price, int scoreRank) { }
}
