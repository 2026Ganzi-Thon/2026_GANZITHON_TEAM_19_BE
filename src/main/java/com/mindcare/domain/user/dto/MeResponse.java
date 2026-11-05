package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * USR-01 응답 (TRD의 MeOut). provider_profile은 프로필 기능을 만들 때 추가한다.
 */
public record MeResponse(
        UUID id,
        UserRole role,
        UserStatus status,
        String nickname,
        Instant suspendedUntil,
        Instant restrictedUntil,
        boolean ethicsTrainingRequired) {

    public static MeResponse from(User user) {
        return new MeResponse(
                user.getId(),
                user.getRole(),
                user.getStatus(),
                user.getNickname(),
                user.getSuspendedUntil(),
                user.getRestrictedUntil(),
                user.isEthicsTrainingRequired());
    }
}
