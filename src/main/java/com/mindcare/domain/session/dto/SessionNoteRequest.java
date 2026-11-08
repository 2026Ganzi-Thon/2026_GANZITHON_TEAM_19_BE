package com.mindcare.domain.session.dto;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
public record SessionNoteRequest(@NotBlank @Size(max = 5000) String note) { }
