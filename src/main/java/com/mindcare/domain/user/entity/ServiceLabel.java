package com.mindcare.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * provider_profiles.service_label. 수련생 서비스 이름 (마음 코칭·리스너·버디). 수련생만 쓴다.
 */
@Getter
@RequiredArgsConstructor
public enum ServiceLabel {
    COACHING("coaching"),
    LISTENER("listener"),
    BUDDY("buddy");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<ServiceLabel, String> {

        @Override
        public String convertToDatabaseColumn(ServiceLabel label) {
            return label == null ? null : label.value;
        }

        @Override
        public ServiceLabel convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(label -> label.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown service label: " + value));
        }
    }
}
