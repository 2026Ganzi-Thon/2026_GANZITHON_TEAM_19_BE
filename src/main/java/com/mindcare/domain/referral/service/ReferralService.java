package com.mindcare.domain.referral.service;
import com.mindcare.domain.job.service.JobService;
import com.mindcare.domain.referral.dto.ReferralDetailResponse;
import com.mindcare.domain.referral.entity.Referral;
import com.mindcare.domain.referral.repository.ReferralRepository;
import com.mindcare.domain.report.entity.Report;
import com.mindcare.domain.report.repository.ReportRepository;
import com.mindcare.domain.session.entity.*;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.user.entity.*;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.domain.kpi.service.KpiEventService;
import com.mindcare.global.error.*;
import java.sql.Timestamp;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class ReferralService { private final ReferralRepository referrals; private final SessionRepository sessions; private final UserService users; private final KpiEventService kpiEvents;
 private final ReportRepository reports; private final JobService jobService; private final JdbcTemplate jdbc;
 static final int MAX_SESSIONS = 10;
 public List<Referral> list(UUID clientId){return referrals.findByClientIdOrderByCreatedAtDesc(clientId);}
 /** 연계를 시작한 회기를 기본 포함 회기로 넣고 연계 리포트 생성을 바로 등록한다. */
 @Transactional public Referral create(UUID clientId,UUID sessionId,String reason){Session s=sessions.findById(sessionId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));if(!clientId.equals(s.getClient().getId()))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);if(s.getStatus()!=SessionStatus.COMPLETED)throw new AppException(ErrorCode.SESSION_NOT_ELIGIBLE);Referral r=referrals.saveAndFlush(new Referral(clientId,sessionId,"client",reason));replaceSessions(r,List.of(sessionId));return r;}
 @Transactional public Referral selectExpert(UUID clientId,UUID referralId,UUID expertId){Referral r=owned(clientId,referralId);if(users.getById(expertId).getRole()!=UserRole.EXPERT)throw new AppException(ErrorCode.VALIDATION_ERROR);try{r.selectExpert(expertId);}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);}kpiEvents.record("referral-selected-"+r.getId(),"referral_accepted",Map.of("source","client"));return r;}
 @Transactional public void cancel(UUID clientId,UUID id,String reason){Referral r=owned(clientId,id);try{r.cancel(reason);}catch(IllegalStateException e){throw new AppException(ErrorCode.INVALID_STATE);}}

 /** 포함 회기 변경: 동의(잠금) 전에만, 내 완료 코칭 회기만. 바뀌면 연계 리포트 버전을 올리고 다시 생성한다. */
 @Transactional public ReferralDetailResponse updateSessions(UUID clientId,UUID referralId,List<UUID> sessionIds){
  Referral r=owned(clientId,referralId);
  if(r.getIncludedLockedAt()!=null||"cancelled".equals(r.getStatus()))throw new AppException(ErrorCode.INVALID_STATE);
  List<UUID> ids=new ArrayList<>(new LinkedHashSet<>(sessionIds));
  if(ids.isEmpty()||ids.size()>MAX_SESSIONS)throw new AppException(ErrorCode.VALIDATION_ERROR);
  Set<UUID> eligible=new HashSet<>(jdbc.queryForList("select id from sessions where client_id = ? and status = 'completed' and track = 'coaching'",UUID.class,clientId));
  if(!eligible.containsAll(ids))throw new AppException(ErrorCode.SESSION_NOT_ELIGIBLE);
  replaceSessions(r,ids);
  return detail(clientId,referralId);
 }

 public ReferralDetailResponse detail(UUID clientId,UUID referralId){
  Referral r=owned(clientId,referralId);
  List<ReferralDetailResponse.EligibleSession> eligible=jdbc.query("""
    select s.id, s.scheduled_start, coalesce(pp.display_name, '상담사') as provider_display, rp.status as report_status,
           row_number() over (order by s.scheduled_start) as session_no
      from sessions s left join provider_profiles pp on pp.user_id = s.provider_id
      left join reports rp on rp.session_id = s.id
     where s.client_id = ? and s.status = 'completed' and s.track = 'coaching'
     order by s.scheduled_start desc
    """,(rs,i)->new ReferralDetailResponse.EligibleSession(rs.getObject("id",UUID.class),rs.getInt("session_no"),
      rs.getTimestamp("scheduled_start").toInstant(),rs.getString("provider_display"),rs.getString("report_status")),clientId);
  List<UUID> included=r.getReportId()==null?List.of():jdbc.queryForList("select session_id from report_sources where report_id = ? order by session_no",UUID.class,r.getReportId());
  List<ReferralDetailResponse.Candidate> candidates=jdbc.query("""
    select pp.user_id, pp.display_name, pp.track, min(s.start_at) as earliest_slot
      from provider_profiles pp join users u on u.id = pp.user_id
      left join availability_slots s on s.provider_id = pp.user_id and s.status = 'open' and s.start_at > now()
     where pp.track = 'expert' and u.status = 'active'
     group by pp.user_id, pp.display_name, pp.track
     order by min(s.start_at) nulls last, pp.display_name
    """,(rs,i)->{Timestamp t=rs.getTimestamp("earliest_slot");return new ReferralDetailResponse.Candidate(rs.getObject("user_id",UUID.class),
      rs.getString("display_name"),rs.getString("track"),t==null?null:t.toInstant(),null);});
  ReferralDetailResponse.ReportRef report=r.getReportId()==null?null:reports.findById(r.getReportId())
    .map(rp->new ReferralDetailResponse.ReportRef(rp.getId(),rp.getStatus(),rp.getVersion())).orElse(null);
  return new ReferralDetailResponse(new ReferralDetailResponse.Referral(r.getId(),r.getSourceSessionId(),r.getExpertId(),r.getStatus(),
    r.getIncludedLockedAt(),r.getCreatedAt()),eligible,included,candidates,report);
 }

 /** 동의(CNS-02) 시점에 포함 회기를 잠근다. */
 @Transactional public void lockSessions(UUID referralId){referrals.findById(referralId).ifPresent(Referral::lockSessions);}

 private void replaceSessions(Referral r,List<UUID> ids){
  Report report;
  if(r.getReportId()==null){report=reports.saveAndFlush(Report.generatingForReferral(r.getId(),r.getClientId()));r.attachReport(report.getId());}
  else{
   report=reports.findById(r.getReportId()).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));
   List<UUID> current=jdbc.queryForList("select session_id from report_sources where report_id = ? order by session_no",UUID.class,report.getId());
   if(new HashSet<>(current).equals(new HashSet<>(ids))&&!"failed".equals(report.getStatus()))return;
   report.sourcesChanged();reports.flush();
  }
  jdbc.update("delete from report_sources where report_id = ?",report.getId());
  // session_no = 포함 회기 안에서의 시간순 번호 (1부터)
  jdbc.update("""
    insert into report_sources (report_id, session_id, session_no)
    select ?, s.id, row_number() over (order by s.scheduled_start) from sessions s where s.id = any(?)
    """,report.getId(),ids.toArray(new UUID[0]));
  jobService.enqueue("generate_referral_report","report:"+report.getId(),Map.of("report_id",report.getId().toString()));
 }
 private Referral owned(UUID clientId,UUID id){Referral r=referrals.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));if(!clientId.equals(r.getClientId()))throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);return r;}
}
