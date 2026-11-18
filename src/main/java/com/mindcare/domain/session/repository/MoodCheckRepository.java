package com.mindcare.domain.session.repository;
import com.mindcare.domain.session.entity.MoodCheck;
import com.mindcare.domain.session.entity.MoodPhase;
import java.util.UUID;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MoodCheckRepository extends JpaRepository<MoodCheck, UUID> {
    boolean existsBySessionIdAndPhase(UUID sessionId, MoodPhase phase);
    List<MoodCheck> findBySessionId(UUID sessionId);
}
