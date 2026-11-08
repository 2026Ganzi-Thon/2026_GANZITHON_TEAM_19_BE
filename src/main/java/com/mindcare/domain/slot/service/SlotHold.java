package com.mindcare.domain.slot.service;

import java.time.Instant;
import java.util.UUID;

/**
 * SlotService.hold() 결과. 예약 생성(SES-01)이 세션을 만들 때 필요한 값을 담는다.
 * track: 슬롯 소유자 역할로 정한다 (수련생 → coaching, 전문가 → professional). price도 그 트랙 설정값.
 */
public record SlotHold(
        UUID slotId,
        UUID providerId,
        String track,
        Instant startAt,
        Instant endAt,
        Instant holdExpiresAt,
        int price) {
}
