package com.mindcare.domain.report.service;

import com.mindcare.domain.intake.repository.IntakeRepository;
import com.mindcare.domain.job.entity.Job;
import com.mindcare.domain.job.entity.JobStatus;
import com.mindcare.domain.job.repository.JobRepository;
import com.mindcare.domain.message.repository.MessageRepository;
import com.mindcare.domain.report.entity.Report;
import com.mindcare.domain.report.repository.ReportRepository;
import com.mindcare.domain.session.entity.Session;
import com.mindcare.domain.session.repository.SessionRepository;
import com.mindcare.global.crypto.SensitiveTextCodec;
import java.time.Duration;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.core.ParameterizedTypeReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * 리포트 잡 한 건을 처리한다. {@link ReportJobRunner}와 다른 빈이어야 프록시를 거쳐 @Transactional이 적용된다
 * (같은 클래스 안에서 호출하면 잡·리포트 상태 변경이 저장되지 않는다).
 */
@Component @RequiredArgsConstructor
public class ReportJobProcessor {
 private final JobRepository jobs; private final ReportRepository reports; private final SessionRepository sessions;
 private final IntakeRepository intakes; private final MessageRepository messages; private final SensitiveTextCodec codec; private final JsonMapper objectMapper;
 @Value("${AI_BASE_URL:}") private String aiBaseUrl; @Value("${AI_INTERNAL_TOKEN:}") private String internalToken;
 @Transactional public void process(UUID jobId) {
  Job job=jobs.findById(jobId).orElse(null); if(job==null) return;
  try { job.start(); } catch(IllegalStateException ignored) { return; }
  UUID reportId=UUID.fromString(job.getPayload().get("report_id")); Report report=reports.findById(reportId).orElse(null);
  if(report==null) { job.succeed(); return; }
  try { Map<String,Object> result=callAi(report); Map<String,Object> summary=(Map<String,Object>) result.get("client_summary");
   report.markReady(codec.encrypt(objectMapper.writeValueAsString(result)),codec.encrypt(objectMapper.writeValueAsString(summary)),risk(result)); job.succeed();
  } catch(Exception e) { job.fail(); if(job.getStatus()==JobStatus.FAILED) report.fail(); }
 }
 private Map<String,Object> callAi(Report report) {
  if(aiBaseUrl==null||aiBaseUrl.isBlank()) throw new IllegalStateException("AI_BASE_URL is not configured");
  Session session=sessions.findById(report.getSessionId()).orElseThrow();
  List<Map<String,String>> transcript=messages.findTop100BySessionIdOrderByIdAsc(session.getId()).stream().map(m->Map.of("role",m.getSenderId().equals(session.getClient().getId())?"client":"provider","text",codec.decrypt(m.getBodyEnc()))).toList();
  if(transcript.isEmpty()) transcript=List.of(Map.of("role","client","text","상담 내용이 기록되지 않았습니다."));
  Map<String,Object> intake=Map.of("category",session.getIntakeId()==null?"other":intakes.findById(session.getIntakeId()).map(i->i.getCategory().getValue()).orElse("other"),"text","", "tags",Map.of());
  SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(15));
  return RestClient.builder().baseUrl(aiBaseUrl).defaultHeader("X-Internal-Token",internalToken==null?"":internalToken).requestFactory(factory).build().post().uri("/v1/reports/session").body(Map.of("transcript",transcript,"intake",intake)).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>(){});
 }
 private String risk(Map<String,Object> result) { Object red=result.get("red_flag"); return red instanceof Map<?,?> map&&map.get("level")!=null?String.valueOf(map.get("level")):"none"; }
}
