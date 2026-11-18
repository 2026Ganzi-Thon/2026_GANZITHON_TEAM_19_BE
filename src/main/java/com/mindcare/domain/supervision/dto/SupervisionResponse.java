package com.mindcare.domain.supervision.dto;
import com.mindcare.domain.supervision.entity.Supervision;
import java.time.Instant;
import java.util.UUID;
public record SupervisionResponse(UUID id, String kind, UUID referralId, UUID requestId, UUID coachingSessionId, UUID expertId, UUID traineeId, String status, Instant dueAt, Instant submittedAt) {
 public static SupervisionResponse from(Supervision s) { return new SupervisionResponse(s.getId(),s.getKind(),s.getReferralId(),s.getRequestId(),s.getCoachingSessionId(),s.getExpertId(),s.getTraineeId(),s.getStatus().name().toLowerCase(),s.getDueAt(),s.getSubmittedAt()); }
}
