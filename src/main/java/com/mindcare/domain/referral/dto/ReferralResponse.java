package com.mindcare.domain.referral.dto;
import com.mindcare.domain.referral.entity.Referral;
import java.time.Instant;
import java.util.UUID;
public record ReferralResponse(UUID id,UUID sourceSessionId,UUID expertId,String status,String reason,Instant createdAt) { public static ReferralResponse from(Referral r){return new ReferralResponse(r.getId(),r.getSourceSessionId(),r.getExpertId(),r.getStatus(),r.getTraineeReason(),r.getCreatedAt());} }
