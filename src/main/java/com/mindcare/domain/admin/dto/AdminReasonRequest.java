package com.mindcare.domain.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 사유만 받는 어드민 요청 (강제 로그아웃 등). 감사 로그에 남는다. */
public record AdminReasonRequest(@NotBlank @Size(max = 500) String reason) {
}
