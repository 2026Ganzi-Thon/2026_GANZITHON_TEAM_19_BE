package com.mindcare.domain.consent.repository;
import com.mindcare.domain.consent.entity.Consent;
import java.util.UUID;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ConsentRepository extends JpaRepository<Consent,UUID>{
 List<Consent> findByUserIdOrderByGrantedAtDesc(UUID userId);
 Optional<Consent> findFirstByReportIdAndTargetExpertIdAndReportVersionAndRevokedAtIsNullOrderByGrantedAtDesc(UUID reportId, UUID expertId, Short version);
}
