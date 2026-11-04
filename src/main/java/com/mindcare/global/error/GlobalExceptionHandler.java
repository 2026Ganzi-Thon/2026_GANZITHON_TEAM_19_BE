package com.mindcare.global.error;

import com.mindcare.global.web.RequestIdFilter;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 모든 예외를 TRD 12-2 오류 형식으로 바꾼다. 내부 메시지는 클라이언트에 노출하지 않는다 (500은 고정 문구).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(AppException.class)
    public ResponseEntity<ErrorResponse> handleApp(AppException e) {
        return respond(e.getErrorCode(), e.getMessage(), e.getDetails());
    }

    // @Valid @RequestBody 검증 실패 → 422 VALIDATION_ERROR, details.fields: [{field, reason}]
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleBodyValidation(MethodArgumentNotValidException e) {
        List<Map<String, String>> fields = e.getBindingResult().getFieldErrors().stream()
                .map(GlobalExceptionHandler::toField)
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(), Map.of("fields", fields));
    }

    // @RequestParam·@PathVariable 검증 실패
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ErrorResponse> handleParamValidation(HandlerMethodValidationException e) {
        List<Map<String, String>> fields = e.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> Map.of(
                                "field", snake(result.getMethodParameter().getParameterName()),
                                "reason", snake(error.getCodes() == null ? "invalid" : lastCode(error.getCodes())))))
                .toList();
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(), Map.of("fields", fields));
    }

    // TRD 12-6: UUID 형식이 아니거나 정의되지 않은 enum 값 → 422
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        List<Map<String, String>> fields = List.of(Map.of("field", snake(e.getName()), "reason", "invalid_format"));
        return respond(ErrorCode.VALIDATION_ERROR, ErrorCode.VALIDATION_ERROR.getMessage(), Map.of("fields", fields));
    }

    // JSON 깨짐, 필수 파라미터·헤더 없음 → 400
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class
    })
    public ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return respond(ErrorCode.BAD_REQUEST, ErrorCode.BAD_REQUEST.getMessage(), Map.of());
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, ErrorCode.NOT_FOUND.getMessage(), Map.of());
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorCode.METHOD_NOT_ALLOWED.getMessage(), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        log.error("Unhandled exception", e);
        return respond(ErrorCode.INTERNAL, ErrorCode.INTERNAL.getMessage(), Map.of());
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, Object> details) {
        return ResponseEntity.status(code.getStatus())
                .body(ErrorResponse.of(code, message, details, RequestIdFilter.current()));
    }

    private static Map<String, String> toField(FieldError error) {
        String reason = error.getCode() == null ? "invalid" : error.getCode();
        return Map.of("field", snake(error.getField()), "reason", snake(reason));
    }

    private static String lastCode(String[] codes) {
        // codes는 "NotBlank.dto.field" ... "NotBlank" 순서라 마지막이 제약 이름
        return codes[codes.length - 1];
    }

    // displayName → display_name, NotBlank → not_blank (응답 JSON이 snake_case라 맞춤)
    static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase();
    }
}
