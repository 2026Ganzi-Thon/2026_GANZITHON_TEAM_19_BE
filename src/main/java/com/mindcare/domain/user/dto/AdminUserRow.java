package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 어드민 회원 목록 한 줄 (ADM-04). 이메일·실명은 넣지 않는다 (FR-ADM-03).
 * display_name은 수련생·전문가만 (프로필을 등록한 경우).
 */
public record AdminUserRow(
        UUID id,
        UserRole role,
        UserStatus status,
        String nickname,
        String displayName,
        Instant suspendedUntil,
        Instant restrictedUntil,
        Instant createdAt) {
}
