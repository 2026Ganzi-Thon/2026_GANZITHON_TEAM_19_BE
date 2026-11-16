package com.mindcare.domain.user.service;

import com.mindcare.domain.slot.service.SlotService;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.ActiveSessionReader;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * USR-03 회원 탈퇴 (TRD 13-1, FR-ACC-06, 6-6).
 * 예정·진행 세션이 있으면 409 ACTIVE_SESSIONS_EXIST. 없으면 withdrawn으로 바꾸고, 상담사면 open 슬롯을 지우고,
 * 커밋 뒤 Firebase 계정을 삭제한다. 개인정보 파기(_enc null, 닉네임 변경)는 30일 뒤 purge_withdrawn 배치 몫이다.
 * SlotService가 UserService를 쓰므로 순환을 피하려고 UserService와 분리했다.
 */
@Service
@RequiredArgsConstructor
public class WithdrawalService {

    private final UserService userService;
    private final SlotService slotService;
    private final ActiveSessionReader activeSessionReader;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Transactional
    public void withdraw(AuthUser auth) {
        if (auth.status() == UserStatus.WITHDRAWN) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        Instant now = clock.instant();
        if (activeSessionReader.existsFor(auth.id(), now)) {
            throw new AppException(ErrorCode.ACTIVE_SESSIONS_EXIST);
        }
        slotService.deleteOpenSlotsOf(auth.id());
        // 슬롯 삭제가 영속성 컨텍스트를 비우므로 사용자는 그 뒤에 읽는다
        User user = userService.getById(auth.id());
        user.withdraw(now);
        events.publishEvent(new TokenRevocation.AccountDeletion(user.getFirebaseUid()));
    }
}
