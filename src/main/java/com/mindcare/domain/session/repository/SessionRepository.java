package com.mindcare.domain.session.repository;
import com.mindcare.domain.session.entity.Session;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionRepository extends JpaRepository<Session, UUID> {
    List<Session> findByClientIdOrderByScheduledStartDesc(UUID clientId);
    List<Session> findByProviderIdAndClientIdAndStatusOrderByScheduledStartDesc(UUID providerId, UUID clientId, com.mindcare.domain.session.entity.SessionStatus status);
}
