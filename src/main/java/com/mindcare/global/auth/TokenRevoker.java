package com.mindcare.global.auth;

/**
 * Firebase refresh token 폐기 (강제 로그아웃). 폐기 뒤에는 기존 ID 토큰도 FirebaseTokenVerifier(check_revoked)에서 401.
 * Firebase 키가 없으면(로컬·CI) 아무것도 하지 않는 구현으로 뜬다.
 */
public interface TokenRevoker {

    void revoke(String firebaseUid);
}
