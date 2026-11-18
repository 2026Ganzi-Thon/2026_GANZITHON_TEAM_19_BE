package com.mindcare.domain.risk.dto;

import com.mindcare.domain.risk.entity.RiskEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record RiskEventResponse(UUID id, UUID sessionId, UUID messageId, UUID clientId, String level,
                                List<String> ruleHits, String status, Instant createdAt) {
    public static RiskEventResponse from(RiskEvent event) {
        return new RiskEventResponse(event.getId(), event.getSessionId(), event.getMessageId(),
                event.getClientId(), event.getLevel(), event.getRuleHits(), event.getStatus(), event.getCreatedAt());
    }
}
