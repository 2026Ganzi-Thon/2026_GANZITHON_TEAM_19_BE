package com.mindcare.domain.supervision.controller;
import com.mindcare.domain.supervision.dto.*;
import com.mindcare.domain.supervision.service.SupervisionService;
import com.mindcare.domain.supervision.service.SupervisionRequestService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor public class SupervisionController { private final SupervisionService service; private final SupervisionRequestService requests;
 @PostMapping("/supervision-requests") @ResponseStatus(HttpStatus.CREATED) public Map<String,UUID> request(@LoginUser AuthUser user,@Valid @RequestBody SupervisionRequestCreate request){user.requireRole(UserRole.TRAINEE);return Map.of("id",requests.create(user.id(),request.sessionId(),request.preferredExpertId(),request.question()));}
 @PostMapping("/supervision-requests/{requestId}/accept") public Map<String,UUID> accept(@LoginUser AuthUser user,@PathVariable UUID requestId){user.requireRole(UserRole.EXPERT);return Map.of("supervision_id",requests.accept(user.id(),requestId));}
 @GetMapping("/supervisions") public List<SupervisionResponse> list(@LoginUser AuthUser user) { user.requireRole(UserRole.TRAINEE,UserRole.EXPERT); return service.list(user.id()).stream().map(SupervisionResponse::from).toList(); }
 @PostMapping("/supervisions/{supervisionId}/submit") @ResponseStatus(HttpStatus.NO_CONTENT) public void submit(@LoginUser AuthUser user,@PathVariable UUID supervisionId,@Valid @RequestBody SupervisionSubmitRequest request) { user.requireRole(UserRole.EXPERT); service.submit(user.id(),supervisionId,request.content()); }
}
