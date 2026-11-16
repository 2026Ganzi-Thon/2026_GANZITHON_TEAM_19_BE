package com.mindcare.domain.session.dto;

import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.entity.SessionStatus;
import java.time.Instant;
import java.util.UUID;

/** SES-01 응답. 프론트는 session_id로 결제 준비(PAY-02)를 부르고, hold_expires_at까지 결제를 끝내야 한다. */
public record SessionCreateResponse(UUID sessionId, SessionStatus status, String track, UUID providerId,
        Instant scheduledStart, Instant scheduledEnd, int price, Instant holdExpiresAt) {

    public static SessionCreateResponse of(Session s, Instant holdExpiresAt) {
        return new SessionCreateResponse(s.getId(), s.getStatus(), s.getTrack(), s.getProvider().getId(),
                s.getScheduledStart(), s.getScheduledEnd(), s.getPrice(), holdExpiresAt);
    }
}
