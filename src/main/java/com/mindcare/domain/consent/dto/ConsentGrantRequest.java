package com.mindcare.domain.consent.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.UUID;

/** General policy consents are registered at sign-up; this endpoint is for explicit transfer consent. */
public record ConsentGrantRequest(@NotBlank String type, @NotBlank String version,
                                  UUID targetExpertId, UUID reportId, Short reportVersion,
                                  UUID referralId) {}
