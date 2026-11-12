package com.mindcare.domain.admin.dto;

import com.mindcare.domain.user.entity.UserStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

/** ADM-05. reason 필수 (FR-ADM-03). until은 suspended·restricted 해제 시각 (선택). */
public record AdminUserStatusRequest(
        @NotNull UserStatus status,
        @NotBlank @Size(max = 500) String reason,
        Instant until) {
}
