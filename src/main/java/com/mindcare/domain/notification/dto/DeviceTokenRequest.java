package com.mindcare.domain.notification.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** USR-04 요청. platform은 지금은 web만 쓴다 (기본값). */
public record DeviceTokenRequest(
        @NotBlank @Size(max = 4096) String token,
        @Pattern(regexp = "^(web|android|ios)$") String platform) {

    public String platformOrWeb() {
        return platform == null ? "web" : platform;
    }
}
