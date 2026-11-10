package com.mindcare.domain.message.repository;
import com.mindcare.domain.session.entity.Session;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionParticipantRepository extends JpaRepository<Session, UUID> { }
