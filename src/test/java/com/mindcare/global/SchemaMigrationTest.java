package com.mindcare.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * V2·V3 마이그레이션이 TRD 11장대로 들어갔는지 확인.
 */
@SpringBootTest
@Transactional
class SchemaMigrationTest {

    private static final String SLOT_INSERT =
            "insert into availability_slots (id, provider_id, start_at, end_at) values (?, ?, ?::timestamptz, ?::timestamptz)";

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void 테이블은_TRD대로_30개다() {
        assertThat(applicationTables()).hasSize(30).contains("jobs", "shedlock");
    }

    @Test
    void users에_토큰_폐기_시각_컬럼이_있다() {
        Integer count = jdbcTemplate.queryForObject("""
                select count(*) from information_schema.columns
                where table_name = 'users' and column_name = 'tokens_revoked_at'
                """, Integer.class);

        assertThat(count).isEqualTo(1);
    }

    @Test
    void 같은_dedup_key로_대기_중인_작업을_중복_등록할_수_없다() {
        String insert = "insert into jobs (id, type, dedup_key) values (?, 'generate_session_report', 'session_report:1')";
        jdbcTemplate.update(insert, UUID.randomUUID());

        assertThatThrownBy(() -> jdbcTemplate.update(insert, UUID.randomUUID()))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void shedlock을_뺀_모든_테이블에_created_at이_있다() {
        List<String> missing = jdbcTemplate.queryForList("""
                select t.table_name from information_schema.tables t
                where t.table_schema = 'public' and t.table_type = 'BASE TABLE'
                  and t.table_name not in ('flyway_schema_history', 'shedlock')
                  and not exists (
                    select 1 from information_schema.columns c
                    where c.table_schema = 'public' and c.table_name = t.table_name and c.column_name = 'created_at'
                      and c.data_type = 'timestamp with time zone' and c.is_nullable = 'NO')
                """, String.class);

        assertThat(missing).isEmpty();
    }

    @Test
    void 같은_상담사의_슬롯은_시간이_겹칠_수_없다() {
        UUID provider = insertUser("겹침테스트");
        jdbcTemplate.update(SLOT_INSERT, UUID.randomUUID(), provider, "2026-11-20T10:00:00Z", "2026-11-20T11:00:00Z");

        assertThatThrownBy(() -> jdbcTemplate.update(
                SLOT_INSERT, UUID.randomUUID(), provider, "2026-11-20T10:30:00Z", "2026-11-20T11:30:00Z"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 정의되지_않은_상태값은_CHECK에서_막힌다() {
        assertThatThrownBy(() -> jdbcTemplate.update(
                "insert into users (id, firebase_uid, role, nickname) values (?, ?, 'superuser', ?)",
                UUID.randomUUID(), "uid-" + UUID.randomUUID(), "역할오류"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private List<String> applicationTables() {
        return jdbcTemplate.queryForList("""
                select table_name from information_schema.tables
                where table_schema = 'public' and table_type = 'BASE TABLE'
                  and table_name <> 'flyway_schema_history'
                """, String.class);
    }

    private UUID insertUser(String nickname) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("insert into users (id, firebase_uid, role, nickname) values (?, ?, 'trainee', ?)",
                id, "uid-" + id, nickname);
        return id;
    }
}
