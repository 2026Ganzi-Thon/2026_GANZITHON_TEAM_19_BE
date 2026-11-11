package com.mindcare.domain.slot.repository;

import com.mindcare.domain.slot.entity.AvailabilitySlot;
import com.mindcare.domain.slot.entity.SlotStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SlotRepository extends JpaRepository<AvailabilitySlot, UUID> {

    List<AvailabilitySlot> findByProviderIdAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(
            UUID providerId, Instant from, Instant to);

    List<AvailabilitySlot> findByProviderIdAndStatusAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAt(
            UUID providerId, SlotStatus status, Instant from, Instant to);

    // TRD 15-12: 같은 슬롯 동시 예약은 원자적 갱신 한 번으로. 0행이면 다른 사람이 먼저 잡은 것
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update availability_slots
               set status = 'held', hold_expires_at = :holdExpiresAt
             where id = :id and status = 'open' and start_at > :now
            """, nativeQuery = true)
    int hold(@Param("id") UUID id, @Param("holdExpiresAt") Instant holdExpiresAt, @Param("now") Instant now);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update availability_slots
               set status = 'booked', session_id = :sessionId, hold_expires_at = null
             where id = :id and status = 'held'
            """, nativeQuery = true)
    int book(@Param("id") UUID id, @Param("sessionId") UUID sessionId);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            update availability_slots
               set status = 'open', session_id = null, hold_expires_at = null
             where id = :id and status in ('held', 'booked')
            """, nativeQuery = true)
    int release(@Param("id") UUID id);
}
