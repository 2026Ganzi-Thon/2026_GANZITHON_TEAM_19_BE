package com.mindcare.domain.slot.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

/**
 * TRD 11-1 availability_slots. 같은 상담사의 시간 겹침은 DB exclusion 제약이 막는다.
 * 상태 변경(hold·book·release)은 동시성 때문에 엔티티가 아니라 SlotRepository의 조건부 UPDATE로만 한다.
 */
@Entity
@Table(name = "availability_slots")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AvailabilitySlot {

    @Id
    @GeneratedValue
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID providerId;

    @Column(nullable = false)
    private Instant startAt;

    @Column(nullable = false)
    private Instant endAt;

    @Column(nullable = false)
    private SlotStatus status;

    private Instant holdExpiresAt;

    private UUID sessionId;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    public static AvailabilitySlot open(UUID providerId, Instant startAt, Instant endAt) {
        AvailabilitySlot slot = new AvailabilitySlot();
        slot.providerId = providerId;
        slot.startAt = startAt;
        slot.endAt = endAt;
        slot.status = SlotStatus.OPEN;
        return slot;
    }
}
