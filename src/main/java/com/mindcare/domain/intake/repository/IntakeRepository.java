package com.mindcare.domain.intake.repository;

import com.mindcare.domain.intake.entity.Intake;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IntakeRepository extends JpaRepository<Intake, UUID> {
    List<Intake> findByClientIdOrderByCreatedAtDesc(UUID clientId);
}
