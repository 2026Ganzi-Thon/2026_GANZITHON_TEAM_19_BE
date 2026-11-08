package com.mindcare.domain.slot.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * availability_slots.status. open(예약 가능) → held(결제 대기 10분) → booked(결제 완료).
 */
@Getter
@RequiredArgsConstructor
public enum SlotStatus {
    OPEN("open"), HELD("held"), BOOKED("booked");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<SlotStatus, String> {
        @Override public String convertToDatabaseColumn(SlotStatus value) { return value == null ? null : value.value; }
        @Override public SlotStatus convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values()).filter(it -> it.value.equals(value)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown slot status: " + value));
        }
    }
}
