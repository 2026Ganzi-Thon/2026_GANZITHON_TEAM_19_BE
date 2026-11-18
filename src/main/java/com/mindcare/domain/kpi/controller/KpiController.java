package com.mindcare.domain.kpi.controller;
import com.mindcare.domain.kpi.dto.KpiResponse;
import com.mindcare.domain.kpi.service.KpiEventService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.*;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor public class KpiController { private final KpiEventService service;
 @GetMapping("/admin/kpi") public KpiResponse dashboard(@LoginUser AuthUser user,@RequestParam(required=false) Instant from,@RequestParam(required=false) Instant to) { user.requireRole(UserRole.ADMIN); Instant end=to==null?Instant.now():to; Instant start=from==null?end.minus(Duration.ofDays(30)):from; return new KpiResponse(start,end,service.counts(start,end)); }
}
