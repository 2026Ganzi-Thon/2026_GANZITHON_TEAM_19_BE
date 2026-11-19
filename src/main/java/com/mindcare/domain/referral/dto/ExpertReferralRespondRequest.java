package com.mindcare.domain.referral.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 전문가의 연계 수락·거절. 거절 사유는 선택. */
public record ExpertReferralRespondRequest(@NotBlank @Pattern(regexp = "accept|decline") String action, @Size(max = 200) String reason) { }
