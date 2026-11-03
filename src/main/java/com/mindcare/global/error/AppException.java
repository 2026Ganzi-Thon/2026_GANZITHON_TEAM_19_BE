package com.mindcare.global.error;

import java.util.Map;
import lombok.Getter;

/**
 * 서비스 코드에서 던지는 예외는 전부 이걸로. GlobalExceptionHandler가 TRD 12-2 오류 형식으로 바꿔 준다.
 *
 * <pre>
 * throw new AppException(ErrorCode.SLOT_TAKEN);
 * throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", session.getStatus()));
 * </pre>
 */
@Getter
public class AppException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Map<String, Object> details;

    public AppException(ErrorCode errorCode) {
        this(errorCode, errorCode.getMessage(), Map.of());
    }

    public AppException(ErrorCode errorCode, Map<String, Object> details) {
        this(errorCode, errorCode.getMessage(), details);
    }

    public AppException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }
}
