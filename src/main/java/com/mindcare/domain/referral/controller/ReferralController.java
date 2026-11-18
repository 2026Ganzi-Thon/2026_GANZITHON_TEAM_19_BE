package com.mindcare.domain.referral.controller;
import com.mindcare.domain.referral.dto.*;
import com.mindcare.domain.referral.service.ReferralService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.global.auth.*;
import jakarta.validation.Valid;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
@RestController @RequiredArgsConstructor public class ReferralController { private final ReferralService service;
 @GetMapping("/referrals") public List<ReferralResponse> list(@LoginUser AuthUser u){u.requireRole(UserRole.CLIENT);return service.list(u.id()).stream().map(ReferralResponse::from).toList();}
 @PostMapping("/referrals") @ResponseStatus(HttpStatus.CREATED) public ReferralResponse create(@LoginUser AuthUser u,@Valid @RequestBody ReferralCreateRequest r){u.requireRole(UserRole.CLIENT);return ReferralResponse.from(service.create(u.id(),r.sourceSessionId(),r.reason()));}
 @PutMapping("/referrals/{referralId}/expert") public ReferralResponse expert(@LoginUser AuthUser u,@PathVariable UUID referralId,@Valid @RequestBody ReferralExpertRequest r){u.requireRole(UserRole.CLIENT);return ReferralResponse.from(service.selectExpert(u.id(),referralId,r.expertId()));}
 @PostMapping("/referrals/{referralId}/cancel") @ResponseStatus(HttpStatus.NO_CONTENT) public void cancel(@LoginUser AuthUser u,@PathVariable UUID referralId,@RequestParam(defaultValue="cancelled") String reason){u.requireRole(UserRole.CLIENT);service.cancel(u.id(),referralId,reason);}
}
