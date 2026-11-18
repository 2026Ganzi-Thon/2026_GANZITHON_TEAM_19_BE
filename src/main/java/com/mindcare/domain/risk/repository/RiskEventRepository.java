package com.mindcare.domain.risk.repository;

import com.mindcare.domain.risk.entity.RiskEvent;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RiskEventRepository extends JpaRepository<RiskEvent, UUID> {
    List<RiskEvent> findByProviderIdOrderByCreatedAtDesc(UUID providerId);
}
