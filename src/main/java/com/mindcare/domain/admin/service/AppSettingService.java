package com.mindcare.domain.admin.service;

import com.mindcare.domain.admin.dto.SettingResponse;
import com.mindcare.domain.admin.dto.SettingUpdateRequest;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * TRD 13-1 ADM-06·07, FR-ADM-04 운영 설정 (price_configs).
 * 가격은 예약할 때 그 시점 값을 sessions.price에 복사하므로, 바꾸면 이후 예약부터 적용된다.
 */
@Service
@RequiredArgsConstructor
public class AppSettingService {

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;
    private final AuditLogService auditLogService;
    private final ApplicationEventPublisher events;

    /** ADM-06. */
    @Transactional(readOnly = true)
    public List<SettingResponse> list(AuthUser admin) {
        admin.requireRole(UserRole.ADMIN);
        return jdbcTemplate.query(
                "select key, value::text as value, updated_by, updated_at from price_configs order by key",
                (rs, i) -> toResponse(rs));
    }

    /** ADM-07. 모르는 키는 404, 형식이 틀리면 422. 바뀐 값은 커밋 뒤 바로 반영된다 (AppConfigService 캐시 비움). */
    @Transactional
    public SettingResponse update(AuthUser admin, String key, SettingUpdateRequest request) {
        admin.requireRole(UserRole.ADMIN);
        if (!SettingSchemas.isKnown(key)) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        SettingSchemas.validate(key, request.value());

        SettingResponse saved = jdbcTemplate.queryForObject("""
                insert into price_configs (key, value, updated_by) values (?, cast(? as jsonb), ?)
                on conflict (key) do update
                   set value = excluded.value, updated_by = excluded.updated_by, updated_at = now()
                returning key, value::text as value, updated_by, updated_at
                """, (rs, i) -> toResponse(rs), key, jsonMapper.writeValueAsString(request.value()), admin.id());

        String reason = request.reason() == null || request.reason().isBlank()
                ? key : key + ": " + request.reason().strip();
        auditLogService.record(admin.id(), "update_setting", "price_config", null, reason);
        events.publishEvent(new AppConfigService.Changed(key));
        return saved;
    }

    private SettingResponse toResponse(ResultSet rs) throws SQLException {
        return new SettingResponse(
                rs.getString("key"),
                jsonMapper.readTree(rs.getString("value")),
                rs.getObject("updated_by", UUID.class),
                rs.getTimestamp("updated_at").toInstant());
    }
}
