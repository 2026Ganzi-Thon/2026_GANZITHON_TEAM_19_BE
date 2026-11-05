package com.mindcare.global.auth;

import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.Arrays;
import java.util.UUID;

/**
 * 요청을 보낸 사용자. 다른 도메인은 User 엔티티 대신 이걸 받는다.
 */
public record AuthUser(UUID id, UserRole role, UserStatus status) {

    /**
     * TRD 12-4 require_role: 역할이 맞고 계정이 active여야 한다.
     * 정지·심사 중 계정도 허용하는 API(예: USR-01)는 이 메서드를 부르지 않는다.
     */
    public void requireRole(UserRole... roles) {
        if (Arrays.stream(roles).noneMatch(allowed -> allowed == role)) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        requireActive();
    }

    public void requireActive() {
        switch (status) {
            case ACTIVE -> { }
            case PENDING_REVIEW -> throw new AppException(ErrorCode.ACCOUNT_PENDING);
            case REJECTED -> throw new AppException(ErrorCode.ACCOUNT_REJECTED);
            case SUSPENDED -> throw new AppException(ErrorCode.ACCOUNT_SUSPENDED);
            case BANNED -> throw new AppException(ErrorCode.ACCOUNT_BANNED);
            case RESTRICTED -> throw new AppException(ErrorCode.CLIENT_RESTRICTED);
            case WITHDRAWN -> throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
    }
}
