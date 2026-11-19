package com.mindcare.domain.referral.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * 전문가 연계 요청 목록(E05) 한 줄. status는 내담자가 이 연계로 전문 상담을 예약했으면 booked로 보여준다.
 * respond_by가 없으면 마지막 변경 + 48시간.
 */
public record ExpertReferralItem(UUID id, String status, String clientNickname, String category, Instant scheduledStart,
        String riskLevel, int includedSessionCount, String traineeReason, Instant respondBy, boolean hasReportConsent,
        UUID reportId, boolean reportReady) { }
