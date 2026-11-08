package com.mindcare.domain.session.dto;
import com.mindcare.domain.session.entity.MoodPhase;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
public record MoodCheckRequest(@NotNull MoodPhase phase, @Min(1) @Max(10) short score) { }
