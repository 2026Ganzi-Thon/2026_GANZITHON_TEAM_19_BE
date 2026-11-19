package com.mindcare.domain.report.service;
import com.mindcare.domain.admin.service.AuditLogService;
import com.mindcare.domain.consent.service.ConsentService;
import com.mindcare.domain.job.service.JobService;
import com.mindcare.domain.report.dto.ReportResponse;
import com.mindcare.domain.report.entity.Report;
import com.mindcare.domain.report.repository.ReportRepository;
import com.mindcare.domain.session.entity.Session;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional(readOnly = true)
public class ReportService {
 private final ReportRepository reports; private final ConsentService consentService; private final AuditLogService auditLogService; private final JobService jobService; private final SensitiveTextCodec codec; private final ReportSources sources;
 @Transactional public void requestForCompletedSession(Session session) { if(reports.findBySessionId(session.getId()).isPresent()) return; Report report=reports.save(Report.generatingForSession(session.getId(),session.getClient().getId(),session.getProvider().getId())); jobService.enqueue("generate_session_report","report:"+report.getId(),Map.of("report_id",report.getId().toString())); }
 /** 내담자는 생성 중·실패 상태도 받는다 (화면이 status로 폴링). 내용은 ready일 때만 채워진다. */
 public ReportResponse clientView(UUID clientId,UUID reportId) { Report report=reports.findById(reportId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); if(!clientId.equals(report.getClientId())) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE); return response(report,false); }
 public ReportResponse expertView(UUID expertId,UUID reportId) { Report report=ready(reportId); consentService.requireActiveReportConsent(reportId,expertId,report.getVersion()); auditLogService.record(expertId,"report.view","report",reportId,"consent_verified"); return response(report,true); }
 @Transactional public void regenerate(UUID reportId) { Report report=reports.findById(reportId).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); report.regenerate(); jobService.enqueue("generate_report","report:"+reportId,Map.of("report_id",reportId.toString())); }
 private Report ready(UUID id) { Report report=reports.findById(id).orElseThrow(()->new AppException(ErrorCode.NOT_FOUND)); if(!"ready".equals(report.getStatus())) throw new AppException(ErrorCode.REPORT_NOT_READY); return report; }
 private ReportResponse response(Report r,boolean expert) { return new ReportResponse(r.getId(),r.getScope(),r.getStatus(),r.getVersion(),expert&&r.getContentEnc()!=null?codec.decrypt(r.getContentEnc()):null,r.getClientSummaryEnc()==null?null:codec.decrypt(r.getClientSummaryEnc()),r.getRiskLevel(),r.getReadyAt(),sources.of(r)); }
}
