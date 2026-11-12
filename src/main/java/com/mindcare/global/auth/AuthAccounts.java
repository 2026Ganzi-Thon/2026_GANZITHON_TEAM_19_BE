package com.mindcare.global.auth;

/**
 * Firebase 로그인 계정 관리 (시드 전용). uid를 정해서 만들고, 이미 있으면 이메일·비밀번호를 맞춰 다시 쓴다.
 * Firebase 키가 없으면(로컬·CI) 아무것도 만들지 않고 uid만 돌려준다.
 */
public interface AuthAccounts {

    /** 있으면 갱신, 없으면 생성. 돌려주는 값은 Firebase uid (= 넘긴 uid). */
    String ensure(String uid, String email, String password, String displayName);

    /** Firebase 키가 있어서 실제 계정을 만드는지 (없으면 비밀번호 검사도 건너뛴다). */
    boolean isReal();
}
