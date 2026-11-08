package com.mindcare.domain.session.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
public record SessionEndRequest(@Min(0) @Max(2) Integer intakeSessionsNeeded) { }
