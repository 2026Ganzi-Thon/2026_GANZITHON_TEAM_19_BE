package com.mindcare.domain.user.repository;

import com.mindcare.domain.user.entity.ProviderProfile;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface ProviderProfileRepository extends JpaRepository<ProviderProfile, UUID> {

    boolean existsByDisplayNameAndUserIdNot(String displayName, UUID userId);

    /** USR-05 cert_names: 승인된 자격 이름만 (자격번호는 내보내지 않음). credentials도 BE-1 도메인. */
    @Query(value = "select cert_name from credentials where user_id = :userId and status = 'approved' "
            + "order by created_at", nativeQuery = true)
    List<String> findApprovedCertNames(UUID userId);
}
