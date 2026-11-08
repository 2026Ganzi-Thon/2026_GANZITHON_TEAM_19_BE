package com.mindcare.domain.session.dto;
import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.entity.SessionStatus;
import java.time.Instant;
import java.util.UUID;
public record SessionResponse(UUID sessionId, String track, SessionStatus status, UUID clientId, UUID providerId,
                              Instant scheduledStart, Instant scheduledEnd, Short rating) {
    public static SessionResponse from(Session s) { return new SessionResponse(s.getId(), s.getTrack(), s.getStatus(),
            s.getClient().getId(), s.getProvider().getId(), s.getScheduledStart(), s.getScheduledEnd(), s.getRating()); }
}
