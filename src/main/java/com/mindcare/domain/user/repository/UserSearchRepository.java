package com.mindcare.domain.user.repository;

import com.mindcare.domain.user.dto.AdminUserRow;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * ADM-04 회원 검색. 닉네임·활동명(provider_profiles) 부분 일치, 최신 가입순 keyset 페이지.
 * 조건이 선택이라 JPQL 대신 SQL로 조립한다.
 */
@Repository
@RequiredArgsConstructor
public class UserSearchRepository {

    private static final UserRole.DbConverter ROLE = new UserRole.DbConverter();
    private static final UserStatus.DbConverter STATUS = new UserStatus.DbConverter();

    private final NamedParameterJdbcTemplate jdbc;

    public List<AdminUserRow> search(String query, UserRole role, UserStatus status, UUID before, int limit) {
        StringBuilder sql = new StringBuilder("""
                select u.id, u.role, u.status, u.nickname, p.display_name,
                       u.suspended_until, u.restricted_until, u.created_at
                  from users u
                  left join provider_profiles p on p.user_id = u.id
                 where 1 = 1
                """);
        MapSqlParameterSource params = new MapSqlParameterSource("limit", limit);
        if (query != null) {
            sql.append(" and (u.nickname ilike :pattern escape '\\' or p.display_name ilike :pattern escape '\\')");
            params.addValue("pattern", "%" + escapeLike(query) + "%");
        }
        if (role != null) {
            sql.append(" and u.role = :role");
            params.addValue("role", ROLE.convertToDatabaseColumn(role));
        }
        if (status != null) {
            sql.append(" and u.status = :status");
            params.addValue("status", STATUS.convertToDatabaseColumn(status));
        }
        if (before != null) {
            sql.append(" and u.id < :before");
            params.addValue("before", before);
        }
        sql.append(" order by u.id desc limit :limit");
        return jdbc.query(sql.toString(), params, (rs, i) -> new AdminUserRow(
                rs.getObject("id", UUID.class),
                ROLE.convertToEntityAttribute(rs.getString("role")),
                STATUS.convertToEntityAttribute(rs.getString("status")),
                rs.getString("nickname"),
                rs.getString("display_name"),
                toInstant(rs.getTimestamp("suspended_until")),
                toInstant(rs.getTimestamp("restricted_until")),
                toInstant(rs.getTimestamp("created_at"))));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static java.time.Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
