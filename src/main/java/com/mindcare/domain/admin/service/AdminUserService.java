package com.mindcare.domain.admin.service;

import com.mindcare.domain.admin.dto.AdminUserStatusRequest;
import com.mindcare.domain.user.dto.AdminUserRow;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.response.CursorCodec;
import com.mindcare.global.response.PageResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TRD 13-1 ADM-04·05, FR-ADM-03 사용자 관리. 상태 변경·강제 로그아웃은 사유와 함께 감사 로그에 남긴다. */
@Service
@RequiredArgsConstructor
public class AdminUserService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    private static final int MAX_QUERY_LENGTH = 50;

    private final UserService userService;
    private final AuditLogService auditLogService;
    private final CursorCodec cursorCodec;

    /** ADM-04. q는 닉네임·활동명 부분 일치. 최신 가입순. */
    @Transactional(readOnly = true)
    public PageResponse<AdminUserRow> search(AuthUser admin, String q, UserRole role, UserStatus status,
            String cursor, Integer limit) {
        admin.requireRole(UserRole.ADMIN);
        int size = limit == null ? DEFAULT_LIMIT : limit;
        if (size < 1 || size > MAX_LIMIT) {
            throw invalid("limit", "range");
        }
        String query = q == null || q.isBlank() ? null : q.strip();
        if (query != null && query.length() > MAX_QUERY_LENGTH) {
            throw invalid("q", "max_length");
        }
        UUID before = cursor == null || cursor.isBlank() ? null : parseCursor(cursor);
        List<AdminUserRow> rows = userService.searchForAdmin(query, role, status, before, size + 1);
        if (rows.size() <= size) {
            return PageResponse.last(rows);
        }
        List<AdminUserRow> page = rows.subList(0, size);
        return PageResponse.of(page, cursorCodec.encode(page.getLast().id().toString()));
    }

    /** ADM-05 상태 변경 (+ active가 아니면 강제 로그아웃). */
    @Transactional
    public AdminUserRow changeStatus(AuthUser admin, UUID userId, AdminUserStatusRequest request) {
        admin.requireRole(UserRole.ADMIN);
        AdminUserRow row = userService.changeStatusByAdmin(admin.id(), userId, request.status(), request.until());
        auditLogService.record(admin.id(), "change_user_status", "user", userId,
                request.status().getValue() + ": " + request.reason().strip());
        return row;
    }

    /** ADM-05 강제 로그아웃만. */
    @Transactional
    public void revokeTokens(AuthUser admin, UUID userId, String reason) {
        admin.requireRole(UserRole.ADMIN);
        userService.revokeTokensByAdmin(admin.id(), userId);
        auditLogService.record(admin.id(), "revoke_tokens", "user", userId, reason.strip());
    }

    private UUID parseCursor(String cursor) {
        String value = cursorCodec.decode(cursor);
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw new AppException(ErrorCode.BAD_REQUEST, "목록 커서가 올바르지 않습니다", Map.of("field", "cursor"));
        }
    }

    private static AppException invalid(String field, String reason) {
        return new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
