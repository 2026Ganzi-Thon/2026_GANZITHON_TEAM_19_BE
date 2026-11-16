package com.mindcare.domain.session.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/** SES-01. rebook_of가 있으면 intake_id는 이전 세션 것을 이어받는다 (요청 값은 무시). */
public record SessionCreateRequest(@NotNull UUID slotId, UUID intakeId, UUID referralId, UUID rebookOf) {
}
