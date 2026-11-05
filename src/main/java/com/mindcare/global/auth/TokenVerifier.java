package com.mindcare.global.auth;

/**
 * 로그인 토큰 검증. 실제 구현은 FirebaseTokenVerifier, 테스트에서는 가짜로 바꿔 끼운다.
 * 검증에 실패하면 AppException(UNAUTHENTICATED)을 던지고, 성공하면 Firebase uid를 돌려준다.
 */
public interface TokenVerifier {

    String verify(String token);
}
