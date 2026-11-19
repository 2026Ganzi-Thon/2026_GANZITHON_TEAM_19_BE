package com.mindcare.domain.referral.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;

/** 연계 리포트에 포함할 코칭 회기 (최대 10개, AI-05 digests 상한). */
public record ReferralSessionsRequest(@NotEmpty @Size(max = 10) List<UUID> sessionIds) { }
