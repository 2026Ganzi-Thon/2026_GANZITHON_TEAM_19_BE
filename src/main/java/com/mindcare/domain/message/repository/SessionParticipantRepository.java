package com.mindcare.domain.message.repository;
import com.mindcare.domain.message.entity.SessionParticipant;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionParticipantRepository extends JpaRepository<SessionParticipant, UUID> { }
