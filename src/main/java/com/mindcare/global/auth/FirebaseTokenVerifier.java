package com.mindcare.global.auth;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * TRD 6-1: Firebase ID 토큰 검증. 폐기된 토큰도 거절한다 (check_revoked).
 */
@Slf4j
@RequiredArgsConstructor
public class FirebaseTokenVerifier implements TokenVerifier {

    // 토큰 자체가 잘못된 경우. 이 외의 실패(공개키 조회 실패 등)는 사용자 잘못이 아니라서 500으로 둔다
    private static final Set<AuthErrorCode> INVALID_TOKEN_CODES = Set.of(
            AuthErrorCode.EXPIRED_ID_TOKEN,
            AuthErrorCode.INVALID_ID_TOKEN,
            AuthErrorCode.REVOKED_ID_TOKEN,
            AuthErrorCode.USER_DISABLED,
            AuthErrorCode.USER_NOT_FOUND);

    private final FirebaseAuth firebaseAuth;

    @Override
    public String verify(String token) {
        try {
            return firebaseAuth.verifyIdToken(token, true).getUid();
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() != null && INVALID_TOKEN_CODES.contains(e.getAuthErrorCode())) {
                throw new AppException(ErrorCode.UNAUTHENTICATED);
            }
            log.error("Firebase token verification failed: {}", e.getAuthErrorCode(), e);
            throw new AppException(ErrorCode.INTERNAL);
        }
    }
}
