package com.mindcare.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** USR-02 요청. 닉네임 문자 규칙은 service에서 (NamePolicy). */
public record NicknameUpdateRequest(@NotBlank @Size(max = 40) String nickname) {
}
