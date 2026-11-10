package com.mindcare.domain.slot.dto;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import java.time.Instant;
import java.util.UUID;

/** SLT-01·02 응답 (상담사 본인용). booked면 session_id로 어떤 예약인지 알 수 있다. */
public record MySlotResponse(UUID id, Instant startAt, Instant endAt, SlotStatus status, UUID sessionId) {

    public static MySlotResponse from(AvailabilitySlot slot) {
        return new MySlotResponse(slot.getId(), slot.getStartAt(), slot.getEndAt(), slot.getStatus(), slot.getSessionId());
    }
}
