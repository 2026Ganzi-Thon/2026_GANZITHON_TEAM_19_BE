package com.mindcare.domain.consent.dto;

import com.mindcare.domain.consent.entity.Consent;
import java.time.Instant;
import java.util.UUID;

public record ConsentResponse(UUID id, String type, String version, UUID targetExpertId,
                              UUID reportId, Short reportVersion, UUID referralId,
                              Instant grantedAt, Instant revokedAt, String revokedReason) {
    public static ConsentResponse from(Consent consent) {
        return new ConsentResponse(consent.getId(), consent.getType(), consent.getVersion(),
                consent.getTargetExpertId(), consent.getReportId(), consent.getReportVersion(),
                consent.getReferralId(), consent.getGrantedAt(), consent.getRevokedAt(),
                consent.getRevokedReason());
    }
}
