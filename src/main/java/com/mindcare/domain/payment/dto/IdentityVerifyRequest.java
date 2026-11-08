package com.mindcare.domain.payment.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/** PAY-01 요청. 휴대폰 번호는 하이픈이 있어도 되고, 저장할 때는 숫자만 남긴다. */
public record IdentityVerifyRequest(
        @NotBlank @Size(max = 30) String name,
        @NotNull @Past LocalDate birth,
        @NotBlank @Pattern(regexp = "^01[0-9]-?\\d{3,4}-?\\d{4}$") String phone) {

    public String normalizedPhone() {
        return phone.replace("-", "");
    }
}
