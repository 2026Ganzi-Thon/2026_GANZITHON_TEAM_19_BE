package com.mindcare.domain.slot.dto;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import java.time.Instant;
import java.util.UUID;

/** SLT-03 응답 (내담자용). 예약 가능한 슬롯만 보여주므로 상태·세션 정보는 없다. */
public record OpenSlotResponse(UUID id, Instant startAt, Instant endAt) {

    public static OpenSlotResponse from(AvailabilitySlot slot) {
        return new OpenSlotResponse(slot.getId(), slot.getStartAt(), slot.getEndAt());
    }
}
