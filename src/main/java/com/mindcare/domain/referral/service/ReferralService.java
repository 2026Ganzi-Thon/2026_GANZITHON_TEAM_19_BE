package com.mindcare.domain.referral.service;
import com.mindcare.domain.referral.entity.Referral;
import com.mindcare.domain.referral.repository.ReferralRepository;
import com.mindcare.domain.session.entity.*;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.user.entity.*;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.domain.kpi.service.KpiEventService;
import com.mindcare.global.error.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class ReferralService { private final ReferralRepository referrals; private final SessionRepository sessions; private final UserService users; private final KpiEventService kpiEvents;
 public List<Referral> list(UUID clientId){return referrals.findByClientIdOrderByCreatedAtDesc(clientId);}
 @Transactional public Referral create(UUID clientId,UUID sessionId,String reason){Session s=sessions.findById(sessionId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));if(!clientId.equals(s.getClient().getId()))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);if(s.getStatus()!=SessionStatus.COMPLETED)throw new AppException(ErrorCode.SESSION_NOT_ELIGIBLE);return referrals.save(new Referral(clientId,sessionId,"client",reason));}
 @Transactional public Referral selectExpert(UUID clientId,UUID referralId,UUID expertId){Referral r=owned(clientId,referralId);if(users.getById(expertId).getRole()!=UserRole.EXPERT)throw new AppException(ErrorCode.VALIDATION_ERROR);try{r.selectExpert(expertId);}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);}kpiEvents.record("referral-selected-"+r.getId(),"referral_accepted",Map.of("source","client"));return r;}
 @Transactional public void cancel(UUID clientId,UUID id,String reason){Referral r=owned(clientId,id);try{r.cancel(reason);}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);}}
 private Referral owned(UUID clientId,UUID id){Referral r=referrals.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));if(!clientId.equals(r.getClientId()))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);return r;}
}
