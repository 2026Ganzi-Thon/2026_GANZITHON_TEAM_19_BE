package com.mindcare.domain.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 결제에 필요한 세션 값만 읽는다 (sessions는 BE-2 소유, 읽기 전용).
 * 세션 상태 변경은 직접 하지 않고 PaymentConfirmedEvent로 세션 도메인에 맡긴다.
 */
@Repository
@RequiredArgsConstructor
public class PaymentSessionReader {

    private final JdbcTemplate jdbcTemplate;

    public Optional<SessionForPayment> find(UUID sessionId) {
        List<SessionForPayment> rows = jdbcTemplate.query("""
                select id, client_id, status, price, slot_id, scheduled_start from sessions where id = ?
                """, (rs, i) -> new SessionForPayment(
                        rs.getObject("id", UUID.class),
                        rs.getObject("client_id", UUID.class),
                        rs.getString("status"),
                        rs.getInt("price"),
                        rs.getObject("slot_id", UUID.class),
                        rs.getTimestamp("scheduled_start").toInstant()),
                sessionId);
        return rows.stream().findFirst();
    }

    public record SessionForPayment(UUID id, UUID clientId, String status, int price, UUID slotId,
            Instant scheduledStart) {

        public boolean isPendingPayment() {
            return "pending_payment".equals(status);
        }
    }
}
