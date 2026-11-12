package com.mindcare.domain.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** ADM-03 요청. 반려(reject)는 사유 필수 (service에서 검사). */
public record CredentialDecisionRequest(
        @NotNull @Pattern(regexp = "^(approve|reject)$") String decision,
        @Size(max = 500) String reason) {

    public boolean approve() {
        return "approve".equals(decision);
    }
}
