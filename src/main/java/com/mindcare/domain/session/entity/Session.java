package com.mindcare.domain.session.entity;

import com.mindcare.domain.user.entity.User;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;
import java.time.Instant;
import jakarta.persistence.Column;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.UuidGenerator;

/** TRD 11 sessions. 생성은 예약(SES-01)에서만 한다. */
@Entity
@Table(name = "sessions")
@Getter
@NoArgsConstructor
public class Session {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "client_id", nullable = false) private User client;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "provider_id", nullable = false) private User provider;
    private SessionStatus status;
    @Column(nullable = false) private String track;
    private UUID intakeId;
    private UUID referralId;
    private UUID rebookOf;
    @Column(nullable = false, updatable = false) private UUID slotId;
    @Column(nullable = false, updatable = false) private int price;
    @Column(nullable = false) private Instant scheduledStart;
    @Column(nullable = false) private Instant scheduledEnd;
    private Instant endedAt;
    private String endedBy;
    private String cancelReason;
    private UUID cancelledBy;
    private byte[] providerNoteEnc;
    private Short rating;
    private byte[] reviewEnc;

    /** TRD 15-1: 예약 생성 → pending_payment. 슬롯 홀드는 호출하는 쪽이 같은 트랜잭션에서 먼저 잡는다. */
    public static Session reserve(User client, User provider, String track, UUID slotId, int price,
            Instant scheduledStart, Instant scheduledEnd, UUID intakeId, UUID referralId, UUID rebookOf) {
        Session session = new Session();
        session.client = client;
        session.provider = provider;
        session.track = track;
        session.slotId = slotId;
        session.price = price;
        session.scheduledStart = scheduledStart;
        session.scheduledEnd = scheduledEnd;
        session.intakeId = intakeId;
        session.referralId = referralId;
        session.rebookOf = rebookOf;
        session.status = SessionStatus.PENDING_PAYMENT;
        return session;
    }

    /** TRD 15-1: 결제 확정 → confirmed. pending_payment가 아니면 거절한다. */
    public void confirm() {
        if (status != SessionStatus.PENDING_PAYMENT) {
            throw new IllegalStateException("Session cannot be confirmed in its current state");
        }
        status = SessionStatus.CONFIRMED;
    }

    public void cancel(UUID actorId, String reason) {
        if (status == SessionStatus.COMPLETED || status == SessionStatus.CANCELLED) {
            throw new IllegalStateException("Session cannot be cancelled in its current state");
        }
        status = SessionStatus.CANCELLED;
        cancelReason = reason;
        cancelledBy = actorId;
    }

    public void complete() {
        if (status != SessionStatus.IN_PROGRESS && status != SessionStatus.CONFIRMED) {
            throw new IllegalStateException("Session cannot be completed in its current state");
        }
        status = SessionStatus.COMPLETED;
        endedAt = Instant.now();
        endedBy = "provider";
    }

    /** TRD 14-1: 첫 provider 입장 시 confirmed → in_progress. 이미 진행 중이면 그대로 둔다. */
    public void start() {
        if (status == SessionStatus.CONFIRMED) {
            status = SessionStatus.IN_PROGRESS;
        }
    }

    public void writeNote(byte[] encryptedNote) { providerNoteEnc = encryptedNote; }
    public void review(short value, byte[] encryptedReview) {
        if (status != SessionStatus.COMPLETED || rating != null) throw new IllegalStateException("Session cannot be reviewed");
        rating = value; reviewEnc = encryptedReview;
    }
}
