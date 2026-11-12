package com.mindcare.global.auth;

import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import lombok.RequiredArgsConstructor;

/** 시드 계정을 Firebase Auth에 만든다 (TRD 19-3 createUser, 있으면 재사용). */
@RequiredArgsConstructor
class FirebaseAuthAccounts implements AuthAccounts {

    private final FirebaseAuth firebaseAuth;

    @Override
    public String ensure(String uid, String email, String password, String displayName) {
        try {
            try {
                firebaseAuth.getUser(uid);
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid)
                        .setEmail(email).setPassword(password).setDisplayName(displayName)
                        .setEmailVerified(true).setDisabled(false));
            } catch (FirebaseAuthException e) {
                if (e.getAuthErrorCode() != AuthErrorCode.USER_NOT_FOUND) {
                    throw e;
                }
                firebaseAuth.createUser(new UserRecord.CreateRequest()
                        .setUid(uid).setEmail(email).setPassword(password).setDisplayName(displayName)
                        .setEmailVerified(true));
            }
            return uid;
        } catch (FirebaseAuthException e) {
            throw new IllegalStateException("Firebase 계정 준비 실패: " + email + " (" + e.getAuthErrorCode() + ")", e);
        }
    }

    @Override
    public boolean isReal() {
        return true;
    }
}
