package com.mindcare.domain.session.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record SessionCancelRequest(@NotBlank @Size(max = 500) String reason) { }
