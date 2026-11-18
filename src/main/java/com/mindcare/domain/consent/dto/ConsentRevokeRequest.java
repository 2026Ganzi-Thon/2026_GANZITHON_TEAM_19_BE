package com.mindcare.domain.consent.dto;

import jakarta.validation.constraints.NotBlank;

public record ConsentRevokeRequest(@NotBlank String reason) {}
