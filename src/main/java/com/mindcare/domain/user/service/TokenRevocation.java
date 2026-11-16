package com.mindcare.domain.user.service;

import com.mindcare.global.auth.AuthAccounts;
import com.mindcare.global.auth.TokenRevoker;
import lombok.extern.slf4j.Slf4j;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 강제 로그아웃은 DB 커밋 뒤에 Firebase로 보낸다 (TRD 13-1 ADM-05: 커밋 후 revokeRefreshTokens).
 * 롤백되면 나가지 않는다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TokenRevocation {

    record Requested(String firebaseUid) {
    }

    /** 탈퇴(USR-03). 커밋 뒤 Firebase 계정을 지운다 (TRD 6-6). */
    record AccountDeletion(String firebaseUid) {
    }

    private final TokenRevoker tokenRevoker;
    private final AuthAccounts authAccounts;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRequested(Requested event) {
        tokenRevoker.revoke(event.firebaseUid());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onAccountDeletion(AccountDeletion event) {
        try {
            authAccounts.delete(event.firebaseUid());
        } catch (RuntimeException e) {
            // DB는 이미 withdrawn이라 역할 API는 requireActive가 막는다. 탈퇴 자체는 유지한다
            log.error("Firebase account delete failed", e);
        }
    }
}
