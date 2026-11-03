package com.mindcare.global.error;

import java.util.Map;

/**
 * TRD 12-2 오류 응답 (모든 4xx·5xx).
 * <pre>
 * { "error": { "code": "SLOT_TAKEN", "message": "...", "details": {}, "request_id": "..." } }
 * </pre>
 */
public record ErrorResponse(Body error) {

    public record Body(String code, String message, Map<String, Object> details, String requestId) {
    }

    public static ErrorResponse of(ErrorCode code, String message, Map<String, Object> details, String requestId) {
        return new ErrorResponse(new Body(code.name(), message, details == null ? Map.of() : details, requestId));
    }
}
