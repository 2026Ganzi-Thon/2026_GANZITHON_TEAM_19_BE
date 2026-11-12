package com.mindcare.domain.settlement.controller;

import com.mindcare.domain.settlement.dto.SettlementPayRequest;
import com.mindcare.domain.settlement.dto.SettlementPayResponse;
import com.mindcare.domain.settlement.dto.SettlementResponse;
import com.mindcare.domain.settlement.dto.SettlementSummaryResponse;
import com.mindcare.domain.settlement.entity.SettlementKind;
import com.mindcare.domain.settlement.entity.SettlementStatus;
import com.mindcare.domain.settlement.service.SettlementService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.response.PageResponse;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SettlementController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);

    private final SettlementService settlementService;

    @Operation(operationId = "STL-01", summary = "내 정산 내역 (월별, 상태별 합계)")
    @GetMapping("/me/settlements")
    public SettlementSummaryResponse listMine(@LoginUser AuthUser user, @RequestParam(required = false) String month) {
        return settlementService.listMine(user, month);
    }

    @Operation(operationId = "STL-02", summary = "정산 목록 (어드민, Accept: text/csv면 CSV)")
    @GetMapping(value = "/admin/settlements", produces = MediaType.APPLICATION_JSON_VALUE)
    public PageResponse<SettlementResponse> search(@LoginUser AuthUser admin,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind) {
        return PageResponse.last(settlementService.search(admin, month, parse(SettlementStatus.class, status),
                parse(SettlementKind.class, kind)));
    }

    @Operation(operationId = "STL-02", summary = "정산 목록 CSV 내보내기", hidden = true)
    @GetMapping(value = "/admin/settlements", produces = "text/csv")
    public ResponseEntity<String> searchCsv(@LoginUser AuthUser admin,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String kind) {
        String csv = settlementService.searchCsv(admin, month, parse(SettlementStatus.class, status),
                parse(SettlementKind.class, kind));
        String filename = "settlements-" + (month == null || month.isBlank() ? "current" : month) + ".csv";
        return ResponseEntity.ok()
                .contentType(TEXT_CSV)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(csv);
    }

    @Operation(operationId = "STL-03", summary = "정산 지급 처리 (pending만, 전부 아니면 409)")
    @PostMapping("/admin/settlements/pay")
    public SettlementPayResponse pay(@LoginUser AuthUser admin, @Valid @RequestBody SettlementPayRequest request) {
        return settlementService.pay(admin, request.ids());
    }

    // 쿼리 파라미터는 소문자 값(pending, session)으로 받는다. 모르는 값은 422
    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.name().equalsIgnoreCase(value)) {
                return constant;
            }
        }
        throw new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", "query", "reason", "invalid_value"))));
    }
}
