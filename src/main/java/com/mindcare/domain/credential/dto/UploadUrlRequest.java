package com.mindcare.domain.credential.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/** CRD-01 요청. PDF·JPG·PNG, 10MB 이하만 (형식 검사는 service). */
public record UploadUrlRequest(
        @NotBlank String contentType,
        @NotNull @Positive @Max(10 * 1024 * 1024) Long size) {
}
