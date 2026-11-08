package com.mindcare.domain.slot.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * SLT-02 요청. starts는 시작 시각(UTC) 목록, repeat_weeks만큼 같은 요일·시각으로 매주 반복한다 (0~4).
 * 길이는 상담사 트랙 설정값(코칭 30분, 전문 50분)이라 요청에 없다.
 */
public record SlotCreateRequest(
        @NotEmpty @Size(max = 50) List<@NotNull Instant> starts,
        @Min(0) @Max(4) Integer repeatWeeks) {

    public int repeatWeeksOrZero() {
        return repeatWeeks == null ? 0 : repeatWeeks;
    }
}
