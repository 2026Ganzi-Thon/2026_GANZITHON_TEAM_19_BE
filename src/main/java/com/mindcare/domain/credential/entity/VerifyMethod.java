package com.mindcare.domain.credential.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** credentials.verify_method. api = 진위 확인 어댑터, ocr = 학회 서류 판독, manual = 어드민이 서류를 직접 봄. */
@Getter
@RequiredArgsConstructor
public enum VerifyMethod {
    API("api"),
    OCR("ocr"),
    MANUAL("manual");

    @JsonValue
    private final String value;

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<VerifyMethod, String> {

        @Override
        public String convertToDatabaseColumn(VerifyMethod item) {
            return item == null ? null : item.value;
        }

        @Override
        public VerifyMethod convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(item -> item.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown VerifyMethod: " + value));
        }
    }
}
