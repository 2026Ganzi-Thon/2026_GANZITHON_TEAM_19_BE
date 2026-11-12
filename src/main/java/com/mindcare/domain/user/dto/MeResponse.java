package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * USR-01·AUTH-01 응답 (TRD의 MeOut). provider_profile은 수련생·전문가가 프로필을 작성했을 때만 있다 (없으면 null).
 */
public record MeResponse(
        UUID id,
        UserRole role,
        UserStatus status,
        String nickname,
        Instant suspendedUntil,
        Instant restrictedUntil,
        boolean ethicsTrainingRequired,
        ProviderProfileResponse providerProfile) {

    public static MeResponse from(User user, ProviderProfileResponse providerProfile) {
        return new MeResponse(
                user.getId(),
                user.getRole(),
                user.getStatus(),
                user.getNickname(),
                user.getSuspendedUntil(),
                user.getRestrictedUntil(),
                user.isEthicsTrainingRequired(),
                providerProfile);
    }
}
