package com.mindcare.domain.admin.dto;

import com.mindcare.domain.user.entity.UserStatus;
import java.util.UUID;

/** ADM-03 응답. 처리된 자격 건수와 바뀐 계정 상태. */
public record CredentialDecisionResponse(UUID userId, int decidedCount, UserStatus userStatus) {
}
