package com.mindcare.domain.user.entity;

import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * provider_profiles.track. 가입 역할(trainee·expert)과 같다.
 */
@Getter
@RequiredArgsConstructor
public enum ProviderTrack {
    TRAINEE("trainee"),
    EXPERT("expert");

    @JsonValue
    private final String value;

    public static ProviderTrack of(UserRole role) {
        return switch (role) {
            case TRAINEE -> TRAINEE;
            case EXPERT -> EXPERT;
            default -> throw new IllegalArgumentException("Not a provider role: " + role);
        };
    }

    @Converter(autoApply = true)
    public static class DbConverter implements AttributeConverter<ProviderTrack, String> {

        @Override
        public String convertToDatabaseColumn(ProviderTrack track) {
            return track == null ? null : track.value;
        }

        @Override
        public ProviderTrack convertToEntityAttribute(String value) {
            return value == null ? null : Arrays.stream(values())
                    .filter(track -> track.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown track: " + value));
        }
    }
}
