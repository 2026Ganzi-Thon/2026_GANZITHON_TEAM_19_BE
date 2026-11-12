package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.ServiceLabel;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * USR-06 요청. PUT이라 매번 전체 값을 보낸다.
 * 활동명 문자 규칙·분야 태그 사전·수련생 service_label 필수는 service에서 검사한다.
 */
public record ProviderProfileRequest(
        @NotBlank @Size(max = 40) String displayName,
        @NotBlank @Size(max = 30) String realName,
        ServiceLabel serviceLabel,
        @NotNull @Size(max = 5) List<@NotBlank String> specialties,
        @Size(max = 1000) String bio,
        @Size(max = 10) List<@Valid CareerItem> career,
        @Size(max = 50) String school,
        Boolean acceptsSupervision) {

    public record CareerItem(
            @NotBlank @Size(max = 50) String org,
            @NotNull @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String from,
            @Pattern(regexp = "^\\d{4}-(0[1-9]|1[0-2])$") String to) {
    }
}
