package com.mindcare.domain.session.entity;

import com.mindcare.domain.user.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UuidGenerator;

@Entity
@Table(name = "mood_checks")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MoodCheck {
    @Id @GeneratedValue @UuidGenerator(style = UuidGenerator.Style.VERSION_7) private UUID id;
    @ManyToOne @JoinColumn(name = "session_id", nullable = false) private Session session;
    @ManyToOne @JoinColumn(name = "client_id", nullable = false) private User client;
    @Column(nullable = false) private MoodPhase phase;
    @Column(nullable = false) private short score;
    @CreationTimestamp @Column(nullable = false, updatable = false) private Instant createdAt;
    public MoodCheck(Session session, User client, MoodPhase phase, short score) { this.session = session; this.client = client; this.phase = phase; this.score = score; }
}
