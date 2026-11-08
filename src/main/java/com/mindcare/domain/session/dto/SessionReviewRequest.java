package com.mindcare.domain.session.dto;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
public record SessionReviewRequest(@Min(1) @Max(5) short rating, @Size(max = 1000) String text) { }
