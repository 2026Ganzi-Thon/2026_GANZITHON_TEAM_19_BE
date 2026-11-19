package com.mindcare.domain.session.repository;
import com.mindcare.domain.session.entity.Session;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionRepository extends JpaRepository<Session, UUID> {
    List<Session> findByClientIdOrderByScheduledStartDesc(UUID clientId);
    /** 내담자로 받은 세션과 상담사로 맡은 세션을 함께 조회한다 (SES-02). */
    List<Session> findByClientIdOrProviderIdOrderByScheduledStartDesc(UUID clientId, UUID providerId);
    List<Session> findByProviderIdAndClientIdAndStatusOrderByScheduledStartDesc(UUID providerId, UUID clientId, com.mindcare.domain.session.entity.SessionStatus status);
}
