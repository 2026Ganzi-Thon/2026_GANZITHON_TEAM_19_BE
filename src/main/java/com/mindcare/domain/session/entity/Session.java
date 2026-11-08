package com.mindcare.domain.session.entity;

import com.mindcare.domain.user.entity.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** Existing V2 sessions table mapping. Creation remains in the booking flow. */
@Entity
@Table(name = "sessions")
@Getter
@NoArgsConstructor
public class Session {
    @Id private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false) private User client;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "provider_id", nullable = false) private User provider;
    private SessionStatus status;
    private String cancelReason;
    private UUID cancelledBy;

    public void cancel(UUID actorId, String reason) {
        if (status == SessionStatus.COMPLETED || status == SessionStatus.CANCELLED) {
            throw new IllegalStateException("Session cannot be cancelled in its current state");
        }
        status = SessionStatus.CANCELLED;
        cancelReason = reason;
        cancelledBy = actorId;
    }

    public void complete() {
        if (status != SessionStatus.IN_PROGRESS && status != SessionStatus.CONFIRMED) {
            throw new IllegalStateException("Session cannot be completed in its current state");
        }
        status = SessionStatus.COMPLETED;
    }
}
