package com.mindcare.domain.intake.dto;

import com.mindcare.domain.intake.entity.Intake;
import com.mindcare.domain.intake.entity.IntakeCategory;
import com.mindcare.domain.intake.entity.RiskLevel;
import java.time.Instant;
import java.util.UUID;

public record IntakeListItemResponse(UUID intakeId, IntakeCategory category, RiskLevel riskLevel, Instant createdAt) {
    public static IntakeListItemResponse from(Intake intake) {
        return new IntakeListItemResponse(intake.getId(), intake.getCategory(), intake.getRiskLevel(), intake.getCreatedAt());
    }
}
