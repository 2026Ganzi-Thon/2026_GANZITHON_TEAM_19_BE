package com.mindcare.domain.referral.dto;
import jakarta.validation.constraints.*;
import java.util.UUID;
public record ReferralCreateRequest(@NotNull UUID sourceSessionId,@Size(max=2000) String reason) {}
