package com.mindcare.domain.consent.service;
import com.mindcare.domain.consent.entity.Consent; import com.mindcare.domain.consent.repository.ConsentRepository; import com.mindcare.global.error.*; import java.util.*; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class ConsentService { private final ConsentRepository consents; private final com.mindcare.domain.referral.repository.ReferralRepository referrals;
 public List<Consent> list(UUID userId) { return consents.findByUserIdOrderByGrantedAtDesc(userId); }
 @Transactional public Consent grant(UUID userId,String type,String version,UUID expertId,UUID reportId,Short reportVersion,UUID referralId) {
  return consents.save(new Consent(userId,type,version,expertId,reportId,reportVersion,referralId));
 }
 @Transactional public Consent grantTransfer(UUID userId,String type,String version,UUID expertId,UUID reportId,Short reportVersion,UUID referralId) {
  if(!Set.of("report_transfer", "transcript_transfer").contains(type)) throw new AppException(ErrorCode.VALIDATION_ERROR);
  if(expertId == null || referralId == null) throw new AppException(ErrorCode.INVALID_STATE);
  if("report_transfer".equals(type) && (reportId == null || reportVersion == null)) throw new AppException(ErrorCode.REPORT_NOT_READY);
  // 동의 시점에 연계의 포함 회기를 잠근다 (이후 PUT /referrals/{id}/sessions 불가)
  referrals.findById(referralId).filter(r -> userId.equals(r.getClientId())).ifPresent(com.mindcare.domain.referral.entity.Referral::lockSessions);
  return grant(userId,type,version,expertId,reportId,reportVersion,referralId);
 }
 @Transactional public void revoke(UUID userId,UUID id,String reason) { Consent consent=consents.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); if(!consent.getUserId().equals(userId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); if("terms".equals(consent.getType())||"privacy".equals(consent.getType())) throw new AppException(ErrorCode.VALIDATION_ERROR); try { consent.revoke(reason); } catch(IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); } }
 public void requireActiveReportConsent(UUID reportId,UUID expertId,Short version) { if(consents.findFirstByReportIdAndTargetExpertIdAndReportVersionAndRevokedAtIsNullOrderByGrantedAtDesc(reportId,expertId,version).isEmpty()) throw new AppException(ErrorCode.NO_CONSENT); }
}
