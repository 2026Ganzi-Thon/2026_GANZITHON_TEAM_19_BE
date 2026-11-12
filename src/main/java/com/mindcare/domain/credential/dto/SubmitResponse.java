package com.mindcare.domain.credential.dto;

import com.mindcare.domain.user.entity.UserStatus;

/** CRD-04 응답. 이번에 제출된 건수와 바뀐 계정 상태. */
public record SubmitResponse(int submittedCount, UserStatus userStatus) {
}
