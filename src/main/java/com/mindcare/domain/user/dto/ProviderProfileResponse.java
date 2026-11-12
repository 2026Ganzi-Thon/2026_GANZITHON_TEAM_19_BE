package com.mindcare.domain.user.dto;

import com.mindcare.domain.user.entity.Career;
import com.mindcare.domain.user.entity.ProviderTrack;
import com.mindcare.domain.user.entity.ServiceLabel;
import java.util.List;

/**
 * 내 프로필 (USR-06 응답, USR-01의 provider_profile). 본인만 보므로 실명·소속을 복호화해서 준다.
 */
public record ProviderProfileResponse(
        ProviderTrack track,
        String displayName,
        String realName,
        ServiceLabel serviceLabel,
        List<String> specialties,
        String bio,
        List<Career> career,
        String school,
        boolean acceptsSupervision) {
}
