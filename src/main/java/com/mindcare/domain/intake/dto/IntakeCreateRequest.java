package com.mindcare.domain.intake.dto;

import com.mindcare.domain.intake.entity.IntakeCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record IntakeCreateRequest(
        @NotNull IntakeCategory category,
        @NotBlank @Size(min = 20, max = 2000) String text,
        boolean shareRaw,
        @Size(max = 4) List<@Size(max = 20) String> preferredTimes,
        boolean notifyWhenAvailable) { }
