package com.mindcare.domain.referral.dto;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;
public record ReferralExpertRequest(@NotNull UUID expertId) {}
