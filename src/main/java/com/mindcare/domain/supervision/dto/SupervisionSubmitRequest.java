package com.mindcare.domain.supervision.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record SupervisionSubmitRequest(@NotBlank @Size(max=10000) String content) {}
