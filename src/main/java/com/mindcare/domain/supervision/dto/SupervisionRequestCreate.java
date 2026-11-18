package com.mindcare.domain.supervision.dto;
import jakarta.validation.constraints.*; import java.util.UUID;
public record SupervisionRequestCreate(@NotNull UUID sessionId, UUID preferredExpertId, @NotBlank @Size(max=5000) String question) {}
