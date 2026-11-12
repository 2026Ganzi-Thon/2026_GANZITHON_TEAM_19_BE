package com.mindcare.domain.settlement.dto;

import com.mindcare.domain.settlement.entity.Settlement;
import com.mindcare.domain.settlement.entity.SettlementKind;
import com.mindcare.domain.settlement.entity.SettlementStatus;
import java.time.Instant;
import java.util.UUID;

/** 정산 한 건 (STL-01·02). */
public record SettlementResponse(
        UUID id,
        UUID providerId,
        SettlementKind kind,
        UUID sessionId,
        UUID supervisionId,
        int gross,
        int fee,
        int net,
        SettlementStatus status,
        String statusReason,
        Instant paidAt,
        Instant createdAt) {

    public static SettlementResponse from(Settlement s) {
        return new SettlementResponse(s.getId(), s.getProviderId(), s.getKind(), s.getSessionId(), s.getSupervisionId(),
                s.getGross(), s.getFee(), s.getNet(), s.getStatus(), s.getStatusReason(), s.getPaidAt(),
                s.getCreatedAt());
    }
}
