package com.mindcare.domain.auth.dto;

import com.mindcare.domain.user.entity.UserRole;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * AUTH-01 요청. 닉네임 규칙·필수 동의 4종·admin 가입 금지는 service에서 검사한다.
 * prior_counseling(이전 상담 경험)은 온보딩 설문 값인데 TRD 11장에 저장할 컬럼이 없어 검증만 하고 저장하지 않는다.
 */
public record RegisterRequest(
        @NotNull UserRole role,
        @NotBlank @Size(max = 40) String nickname,
        @NotEmpty @Size(max = 10) List<@Valid ConsentItem> consents,
        // Jackson 3은 빠진 primitive 필드를 오류(400)로 보므로 선택 항목은 Boolean으로 받는다
        Boolean trainingDataOptIn,
        Boolean marketingOptIn,
        @Pattern(regexp = "^(yes|no|skip)$") String priorCounseling) {

    public boolean trainingDataOptInOrFalse() {
        return Boolean.TRUE.equals(trainingDataOptIn);
    }

    public boolean marketingOptInOrFalse() {
        return Boolean.TRUE.equals(marketingOptIn);
    }

    public record ConsentItem(
            @NotBlank @Size(max = 30) String type,
            @NotBlank @Size(max = 20) String version) {
    }
}
