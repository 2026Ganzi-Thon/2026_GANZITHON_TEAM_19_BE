package com.mindcare.global.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

/**
 * TRD 12-3 오류 코드 표 그대로. 새 코드가 필요하면 TRD에 먼저 추가하고 여기에 넣는다.
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // 400
    BAD_REQUEST(HttpStatus.BAD_REQUEST, "요청 형식이 올바르지 않습니다"),

    // 401
    UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "로그인이 필요합니다"),

    // 403 권한
    FORBIDDEN_ROLE(HttpStatus.FORBIDDEN, "이 기능을 사용할 수 있는 역할이 아닙니다"),
    FORBIDDEN_RESOURCE(HttpStatus.FORBIDDEN, "접근할 수 없는 자원입니다"),
    // 403 계정 상태
    ACCOUNT_PENDING(HttpStatus.FORBIDDEN, "자격 심사 중인 계정입니다"),
    ACCOUNT_REJECTED(HttpStatus.FORBIDDEN, "자격 심사가 반려된 계정입니다"),
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "이용이 정지된 계정입니다"),
    ACCOUNT_BANNED(HttpStatus.FORBIDDEN, "이용이 영구 정지된 계정입니다"),
    CLIENT_RESTRICTED(HttpStatus.FORBIDDEN, "예약이 제한된 계정입니다"),
    // 403 동의
    NO_CONSENT(HttpStatus.FORBIDDEN, "열람 동의가 없습니다"),
    CONSENT_REVOKED(HttpStatus.FORBIDDEN, "열람 동의가 철회되었습니다"),
    FEATURE_DISABLED(HttpStatus.FORBIDDEN, "지금은 사용할 수 없는 기능입니다"),

    // 404
    USER_NOT_REGISTERED(HttpStatus.NOT_FOUND, "가입을 먼저 완료해 주세요"),
    NOT_FOUND(HttpStatus.NOT_FOUND, "찾을 수 없습니다"),

    // 405 (TRD 표에는 없음, 형식 통일용)
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "지원하지 않는 요청 방식입니다"),

    // 409 상태 충돌
    ALREADY_REGISTERED(HttpStatus.CONFLICT, "이미 가입된 계정입니다"),
    NICKNAME_TAKEN(HttpStatus.CONFLICT, "이미 사용 중인 닉네임입니다"),
    SLOT_TAKEN(HttpStatus.CONFLICT, "이미 예약된 시간입니다"),
    SLOT_BOOKED(HttpStatus.CONFLICT, "예약이 있는 시간은 바꿀 수 없습니다"),
    COMPLAINT_EXISTS(HttpStatus.CONFLICT, "이미 접수된 민원이 있습니다"),
    ALREADY_SUBMITTED(HttpStatus.CONFLICT, "이미 제출되었습니다"),
    ACTIVE_SESSIONS_EXIST(HttpStatus.CONFLICT, "예정되거나 진행 중인 상담이 있습니다"),
    INVALID_STATE(HttpStatus.CONFLICT, "지금 상태에서는 할 수 없는 요청입니다"),
    REPORT_NOT_READY(HttpStatus.CONFLICT, "리포트를 만드는 중입니다"),
    INCLUDED_LOCKED(HttpStatus.CONFLICT, "동의한 뒤에는 포함할 상담을 바꿀 수 없습니다"),

    // 410
    HOLD_EXPIRED(HttpStatus.GONE, "예약 대기 시간이 지났습니다"),

    // 422 검증
    VALIDATION_ERROR(HttpStatus.UNPROCESSABLE_CONTENT, "입력값을 확인해 주세요"),
    SLOT_OVERLAP(HttpStatus.UNPROCESSABLE_CONTENT, "다른 시간과 겹칩니다"),
    CREDENTIAL_MISMATCH(HttpStatus.UNPROCESSABLE_CONTENT, "자격 정보가 일치하지 않습니다"),
    SUPERVISION_INVALID(HttpStatus.UNPROCESSABLE_CONTENT, "작성 조건을 채우지 못한 항목이 있습니다"),
    SESSION_NOT_ELIGIBLE(HttpStatus.UNPROCESSABLE_CONTENT, "포함할 수 없는 상담입니다"),

    // 429
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "요청이 너무 많습니다. 잠시 후 다시 시도해 주세요"),

    // 500·503
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR, "일시적인 오류가 발생했습니다"),
    VERIFIER_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "자격 확인 서비스에 연결할 수 없습니다"),
    AI_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "AI 서비스에 연결할 수 없습니다"),
    MAINTENANCE(HttpStatus.SERVICE_UNAVAILABLE, "점검 중입니다");

    private final HttpStatus status;
    private final String message;
}
