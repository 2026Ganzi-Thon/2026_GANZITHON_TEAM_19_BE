package com.mindcare.domain.user.repository;

import java.time.Instant;
import java.sql.Timestamp;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 탈퇴(USR-03) 가능 여부만 본다 (sessions는 BE-2 소유, 읽기 전용).
 * 예정·진행 세션 = confirmed · in_progress, 그리고 홀드가 아직 살아 있는 pending_payment.
 * 홀드가 끝난 결제 대기 세션은 결제할 수 없으므로 탈퇴를 막지 않는다.
 */
@Repository
@RequiredArgsConstructor
public class ActiveSessionReader {

    private final JdbcTemplate jdbcTemplate;

    public boolean existsFor(UUID userId, Instant now) {
        Boolean exists = jdbcTemplate.queryForObject("""
                select exists (
                  select 1 from sessions s
                    join availability_slots a on a.id = s.slot_id
                   where (s.client_id = ? or s.provider_id = ?)
                     and (s.status in ('confirmed', 'in_progress')
                          or (s.status = 'pending_payment' and a.status = 'held' and a.hold_expires_at > ?))
                )
                """, Boolean.class, userId, userId, Timestamp.from(now));
        return Boolean.TRUE.equals(exists);
    }
}
