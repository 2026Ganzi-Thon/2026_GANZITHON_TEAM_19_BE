package com.mindcare.domain.message.dto;
import java.time.Instant;
import java.util.UUID;
public record MessageResponse(UUID id, UUID sessionId, UUID senderId, String body, String riskLevel, Instant createdAt) { }
