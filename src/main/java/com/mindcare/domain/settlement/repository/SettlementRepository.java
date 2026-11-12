package com.mindcare.domain.settlement.repository;

import com.mindcare.domain.settlement.entity.Settlement;
import com.mindcare.domain.settlement.entity.SettlementKind;
import com.mindcare.domain.settlement.entity.SettlementStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface SettlementRepository extends JpaRepository<Settlement, UUID> {

    Optional<Settlement> findBySessionId(UUID sessionId);

    Optional<Settlement> findBySupervisionId(UUID supervisionId);

    List<Settlement> findByProviderIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThanOrderByCreatedAtDesc(
            UUID providerId, Instant from, Instant to);

    /** STL-02. 필터는 null이면 전체. */
    @Query("""
            select s from Settlement s
             where s.createdAt >= :from and s.createdAt < :to
               and (:status is null or s.status = :status)
               and (:kind is null or s.kind = :kind)
             order by s.createdAt desc""")
    List<Settlement> search(Instant from, Instant to, SettlementStatus status, SettlementKind kind);

    /** STL-03. 지급 처리 중 다른 변경(잠금 해제·보류)과 겹치지 않게 행을 잠근다. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Settlement s where s.id in :ids")
    List<Settlement> findAllForUpdate(Collection<UUID> ids);
}
