package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.Career;
import com.mindcare.domain.user.entity.ProviderTrack;
import com.mindcare.domain.user.entity.ServiceLabel;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * USR-05 응답 (내담자가 보는 상담사 프로필). 실명·자격번호·소속은 넣지 않는다.
 */
public record ProviderPublicResponse(
        UUID providerId,
        String displayName,
        ProviderTrack track,
        ServiceLabel serviceLabel,
        List<String> certNames,
        List<String> specialties,
        String bio,
        List<Career> career,
        BigDecimal ratingAvg,
        int ratingCount) {
}
