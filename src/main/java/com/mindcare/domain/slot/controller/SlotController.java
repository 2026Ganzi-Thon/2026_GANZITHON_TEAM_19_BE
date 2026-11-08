package com.mindcare.domain.slot.controller;

import com.mindcare.domain.slot.dto.MySlotResponse;
import com.mindcare.domain.slot.dto.OpenSlotResponse;
import com.mindcare.domain.slot.dto.SlotCreateRequest;
import com.mindcare.domain.slot.service.SlotService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class SlotController {

    private final SlotService slotService;

    @Operation(operationId = "SLT-01", summary = "내 슬롯 조회 (기본: 지금부터 28일)")
    @GetMapping("/me/slots")
    public List<MySlotResponse> listMine(@LoginUser AuthUser user,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return slotService.listMine(user, from, to);
    }

    @Operation(operationId = "SLT-02", summary = "슬롯 등록 (길이는 트랙 설정값, 매주 반복 0~4주)")
    @PostMapping("/me/slots")
    @ResponseStatus(HttpStatus.CREATED)
    public List<MySlotResponse> create(@LoginUser AuthUser user, @Valid @RequestBody SlotCreateRequest request) {
        return slotService.create(user, request);
    }

    @Operation(operationId = "SLT-03", summary = "상담사의 예약 가능한 슬롯 조회 (최대 14일)")
    @GetMapping("/providers/{providerId}/slots")
    public List<OpenSlotResponse> listOpen(@LoginUser AuthUser user, @PathVariable UUID providerId,
            @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant to) {
        return slotService.listOpen(user, providerId, from, to);
    }

    @Operation(operationId = "SLT-04", summary = "슬롯 삭제 (예약 대기·완료된 슬롯은 불가)")
    @DeleteMapping("/me/slots/{slotId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@LoginUser AuthUser user, @PathVariable UUID slotId) {
        slotService.delete(user, slotId);
    }
}
