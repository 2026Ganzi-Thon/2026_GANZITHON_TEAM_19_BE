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
 private static final java.time.ZoneId KST=java.time.ZoneId.of("Asia/Seoul");
 private static final Set<String> LEVELS=Set.of("none","low","medium","high","critical");
 private final JobRepository jobs; private final ReportRepository reports; private final SessionRepository sessions;
 private final IntakeRepository intakes; private final MessageRepository messages; private final SensitiveTextCodec codec; private final JsonMapper objectMapper;
 private final org.springframework.jdbc.core.JdbcTemplate jdbc;
 @Value("${AI_BASE_URL:}") private String aiBaseUrl; @Value("${AI_INTERNAL_TOKEN:}") private String internalToken;
 @Transactional public void process(UUID jobId) {
  Job job=jobs.findById(jobId).orElse(null); if(job==null) return;
  try { job.start(); } catch(IllegalStateException ignored) { return; }
  UUID reportId=UUID.fromString(job.getPayload().get("report_id")); Report report=reports.findById(reportId).orElse(null);
  if(report==null) { job.succeed(); return; }
  try {
   boolean referral="referral".equals(report.getScope());
   Map<String,Object> result=referral?callAiReferral(report):callAi(report); Map<String,Object> summary=(Map<String,Object>) result.get("client_summary");
   result.remove("meta");
   if(!referral&&result.get("digest")!=null) report.storeDigest(codec.encrypt(objectMapper.writeValueAsString(result.get("digest"))));
   report.markReady(codec.encrypt(objectMapper.writeValueAsString(result)),codec.encrypt(objectMapper.writeValueAsString(summary)),referral?referralRisk(result):risk(result)); job.succeed();
  } catch(Exception e) { job.fail(); if(job.getStatus()==JobStatus.FAILED) report.fail(); }
 }
 private RestClient ai(int readSeconds) {
  if(aiBaseUrl==null||aiBaseUrl.isBlank()) throw new IllegalStateException("AI_BASE_URL is not configured");
  SimpleClientHttpRequestFactory factory=new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(Duration.ofSeconds(2)); factory.setReadTimeout(Duration.ofSeconds(readSeconds));
  return RestClient.builder().baseUrl(aiBaseUrl).defaultHeader("X-Internal-Token",internalToken==null?"":internalToken).requestFactory(factory).build();
 }
 private Map<String,Object> callAi(Report report) {
  RestClient client=ai(15);
  Session session=sessions.findById(report.getSessionId()).orElseThrow();
  List<Map<String,String>> transcript=messages.findTop100BySessionIdOrderByIdAsc(session.getId()).stream().map(m->Map.of("role",m.getSenderId().equals(session.getClient().getId())?"client":"provider","text",codec.decrypt(m.getBodyEnc()))).toList();
  if(transcript.isEmpty()) transcript=List.of(Map.of("role","client","text","상담 내용이 기록되지 않았습니다."));
  Map<String,Object> intake=Map.of("category",session.getIntakeId()==null?"other":intakes.findById(session.getIntakeId()).map(i->i.getCategory().getValue()).orElse("other"),"text","", "tags",Map.of());
  return new HashMap<>(client.post().uri("/v1/reports/session").body(Map.of("transcript",transcript,"intake",intake)).retrieve().body(new ParameterizedTypeReference<Map<String,Object>>(){}));
 }
 /** AI-05: 포함 회기마다 세션 리포트의 digest를 모아 연계 종합 리포트를 만든다. digest가 없는 회기는 빈 digest로 보낸다. */
 private Map<String,Object> callAiReferral(Report report) throws Exception {
  RestClient client=ai(30);
  List<Map<String,Object>> rows=jdbc.queryForList("""
    select rs.session_no, s.scheduled_start, s.provider_id, sr.digest_enc, sr.risk_level
      from report_sources rs join sessions s on s.id = rs.session_id
      left join reports sr on sr.session_id = s.id
     where rs.report_id = ? order by rs.session_no
    """, report.getId());
  if(rows.isEmpty()) throw new IllegalStateException("referral report has no sources");
  Map<Object,String> labels=new LinkedHashMap<>();
  List<Map<String,Object>> digests=new ArrayList<>(); List<Map<String,Object>> riskHistory=new ArrayList<>();
  for(Map<String,Object> row:rows) {
   int no=((Number) row.get("session_no")).intValue();
   String label=labels.computeIfAbsent(row.get("provider_id"),k->labels.isEmpty()?"수련생 A":"수련생 B");
   String level=row.get("risk_level")==null||!LEVELS.contains(String.valueOf(row.get("risk_level")))?"none":String.valueOf(row.get("risk_level"));
   Map<String,Object> digest=new LinkedHashMap<>(Map.of("issues",List.of(),"key_quotes",List.of(),"mood_shift","","risk_level",level,"trainee_interventions",List.of()));
   if(row.get("digest_enc") instanceof byte[] enc) digest.putAll(objectMapper.readValue(codec.decrypt(enc),new tools.jackson.core.type.TypeReference<Map<String,Object>>(){}));
   java.time.LocalDate date=((java.sql.Timestamp) row.get("scheduled_start")).toInstant().atZone(KST).toLocalDate();
   digests.add(Map.of("session_no",no,"session_date",date.toString(),"trainee_label",label,"digest",digest));
   if(!"none".equals(level)) riskHistory.add(Map.of("session_no",no,"level",level));
  }
  return new HashMap<>(client.post().uri("/v1/reports/referral").body(Map.of("digests",digests,"intake_summaries",List.of(),"risk_history",riskHistory))
    .retrieve().body(new ParameterizedTypeReference<Map<String,Object>>(){}));
 }
 private String risk(Map<String,Object> result) { Object red=result.get("red_flag"); return red instanceof Map<?,?> map&&map.get("level")!=null?String.valueOf(map.get("level")):"none"; }
 private String referralRisk(Map<String,Object> result) { Object red=result.get("red_flag"); return red instanceof Map<?,?> map&&map.get("max_level")!=null?String.valueOf(map.get("max_level")):"none"; }
}
