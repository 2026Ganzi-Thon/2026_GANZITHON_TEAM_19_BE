package com.mindcare.domain.risk.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RiskResolutionRequest(@NotBlank @Size(max = 2000) String note) {}
