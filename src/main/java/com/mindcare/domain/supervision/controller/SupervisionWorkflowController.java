package com.mindcare.domain.supervision.controller;

import com.mindcare.domain.supervision.dto.SupervisionWriteRequest;
import com.mindcare.domain.supervision.service.SupervisionWorkflowService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.auth.LoginUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/** 슈퍼비전 화면용 API (전문가 E08 / 수련생 T07). */
@RestController
@RequiredArgsConstructor
public class SupervisionWorkflowController {
    private final SupervisionWorkflowService service;

    @Operation(operationId = "SUP-04", summary = "전문가 슈퍼비전 목록")
    @GetMapping("/expert/supervisions")
    public List<Map<String, Object>> expertList(@LoginUser AuthUser user) {
        user.requireRole(UserRole.EXPERT);
        return service.expertList(user.id());
    }

    @Operation(operationId = "SUP-05", summary = "슈퍼비전 작성 화면 데이터 (수련생 회기·대화록·연계 리포트·저장본)")
    @GetMapping("/expert/supervisions/{supervisionId}/context")
    public Map<String, Object> context(@LoginUser AuthUser user, @PathVariable UUID supervisionId) {
        user.requireRole(UserRole.EXPERT);
        return service.context(user.id(), supervisionId);
    }

    @Operation(operationId = "SUP-06", summary = "슈퍼비전 임시 저장·제출")
    @PutMapping("/expert/supervisions/{supervisionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void write(@LoginUser AuthUser user, @PathVariable UUID supervisionId, @Valid @RequestBody SupervisionWriteRequest request) {
        user.requireRole(UserRole.EXPERT);
        service.write(user.id(), supervisionId, request);
    }

    @Operation(operationId = "SUP-07", summary = "수련생이 받은 슈퍼비전 목록")
    @GetMapping("/trainee/supervisions")
    public List<Map<String, Object>> traineeList(@LoginUser AuthUser user) {
        user.requireRole(UserRole.TRAINEE);
        return service.traineeList(user.id());
    }

    @Operation(operationId = "SUP-08", summary = "수련생 슈퍼비전 상세 (읽음 처리)")
    @GetMapping("/trainee/supervisions/{supervisionId}")
    public Map<String, Object> traineeDetail(@LoginUser AuthUser user, @PathVariable UUID supervisionId) {
        user.requireRole(UserRole.TRAINEE);
        return service.traineeDetail(user.id(), supervisionId);
    }
}
