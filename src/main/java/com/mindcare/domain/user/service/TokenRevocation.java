package com.mindcare.domain.user.service;

import com.mindcare.global.auth.TokenRevoker;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 강제 로그아웃은 DB 커밋 뒤에 Firebase로 보낸다 (TRD 13-1 ADM-05: 커밋 후 revokeRefreshTokens).
 * 롤백되면 나가지 않는다.
 */
@Component
@RequiredArgsConstructor
class TokenRevocation {

    record Requested(String firebaseUid) {
    }

    private final TokenRevoker tokenRevoker;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    void onRequested(Requested event) {
        tokenRevoker.revoke(event.firebaseUid());
    }
}
