package com.mindcare.domain.supervision.service;
import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.session.entity.*;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.domain.supervision.entity.Supervision;
import com.mindcare.domain.supervision.repository.SupervisionRepository;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly=true) public class SupervisionRequestService {
 private final JdbcTemplate jdbc; private final SessionRepository sessions; private final UserService users; private final SupervisionRepository supervisions; private final SensitiveTextCodec codec; private final AppConfigService config;
 @Transactional public UUID create(UUID traineeId,UUID sessionId,UUID preferred,String question){ if(!config.isEnabled("p1_supervision_requests"))throw new AppException(ErrorCode.FEATURE_DISABLED); Session s=sessions.findById(sessionId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND));if(!traineeId.equals(s.getProvider().getId())||s.getStatus()!=SessionStatus.COMPLETED)throw new AppException(ErrorCode.SESSION_NOT_ELIGIBLE);if(preferred!=null&&users.getById(preferred).getRole()!=UserRole.EXPERT)throw new AppException(ErrorCode.VALIDATION_ERROR);UUID id=UUID.randomUUID();jdbc.update("insert into supervision_requests (id, trainee_id, session_id, preferred_expert_id, question_enc, fee, status) values (?,?,?,?,?,0,'pending')",id,traineeId,sessionId,preferred,codec.encrypt(question));return id; }
 @Transactional public UUID accept(UUID expertId,UUID requestId){ if(!config.isEnabled("p1_supervision_requests"))throw new AppException(ErrorCode.FEATURE_DISABLED); Map<String,Object> row=jdbc.queryForMap("select trainee_id, session_id, status from supervision_requests where id=?",requestId);if(!"pending".equals(row.get("status")))throw new AppException(ErrorCode.INVALID_STATE);UUID trainee=(UUID)row.get("trainee_id"),session=(UUID)row.get("session_id");jdbc.update("update supervision_requests set assigned_expert_id=?, status='accepted', accepted_at=now() where id=?",expertId,requestId);return supervisions.save(Supervision.forRequest(requestId,session,expertId,trainee)).getId(); }
}
