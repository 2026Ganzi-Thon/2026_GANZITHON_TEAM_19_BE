package com.mindcare.domain.session.repository;
import com.mindcare.domain.session.entity.Session;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface SessionRepository extends JpaRepository<Session, UUID> { }
