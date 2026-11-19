package com.mindcare.domain.report.controller;
import com.mindcare.domain.report.dto.ReportResponse;
import com.mindcare.domain.report.service.ReportService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.*;
import io.swagger.v3.oas.annotations.Operation;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor public class ReportController { private final ReportService service;
 @Operation(operationId="RPT-01") @GetMapping("/me/reports/{reportId}") public ReportResponse clientView(@LoginUser AuthUser user,@PathVariable UUID reportId) { user.requireRole(UserRole.CLIENT); return service.clientView(user.id(),reportId); }
 @Operation(operationId="RPT-02") @GetMapping("/expert/reports/{reportId}") public com.mindcare.domain.report.dto.ExpertReportResponse expertView(@LoginUser AuthUser user,@PathVariable UUID reportId) { user.requireRole(UserRole.EXPERT); return service.expertView(user.id(),reportId); }
 @Operation(operationId="RPT-03") @PostMapping("/admin/reports/{reportId}/regenerate") @ResponseStatus(HttpStatus.NO_CONTENT) public void regenerate(@LoginUser AuthUser user,@PathVariable UUID reportId) { user.requireRole(UserRole.ADMIN); service.regenerate(reportId); }
}
