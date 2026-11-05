package com.mindcare.global.auth;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class AuthUserTest {

    @Test
    void 역할이_맞고_active면_통과() {
        AuthUser user = new AuthUser(UUID.randomUUID(), UserRole.CLIENT, UserStatus.ACTIVE);

        assertThatCode(() -> user.requireRole(UserRole.CLIENT, UserRole.ADMIN)).doesNotThrowAnyException();
    }

    @Test
    void 역할이_다르면_FORBIDDEN_ROLE() {
        AuthUser user = new AuthUser(UUID.randomUUID(), UserRole.CLIENT, UserStatus.ACTIVE);

        assertThatThrownBy(() -> user.requireRole(UserRole.EXPERT))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.FORBIDDEN_ROLE);
    }

    @ParameterizedTest
    @CsvSource({
            "PENDING_REVIEW, ACCOUNT_PENDING",
            "REJECTED, ACCOUNT_REJECTED",
            "SUSPENDED, ACCOUNT_SUSPENDED",
            "BANNED, ACCOUNT_BANNED",
            "RESTRICTED, CLIENT_RESTRICTED",
            "WITHDRAWN, UNAUTHENTICATED"
    })
    void 역할이_맞아도_계정_상태가_active가_아니면_막힌다(UserStatus status, ErrorCode expected) {
        AuthUser user = new AuthUser(UUID.randomUUID(), UserRole.TRAINEE, status);

        assertThatThrownBy(() -> user.requireRole(UserRole.TRAINEE))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
