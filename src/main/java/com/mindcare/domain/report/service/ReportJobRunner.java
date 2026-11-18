package com.mindcare.domain.report.service;

import com.mindcare.domain.job.entity.Job;
import com.mindcare.domain.job.entity.JobStatus;
import com.mindcare.domain.job.repository.JobRepository;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Polls durable report jobs; the report never becomes ready until the internal AI response is stored. */
@Slf4j
@Component @RequiredArgsConstructor
public class ReportJobRunner {
 private final JobRepository jobs; private final ReportJobProcessor processor;
 @Scheduled(fixedDelayString = "${app.jobs.report-delay-ms:5000}") public void run() {
  for(Job job: jobs.findTop10ByStatusAndRunAtLessThanEqualOrderByCreatedAtAsc(JobStatus.QUEUED, Instant.now())) {
   if(!"generate_session_report".equals(job.getType()) && !"generate_report".equals(job.getType())) continue;
   // 한 건이 예외로 끝나도 나머지 잡은 계속 처리한다
   try { processor.process(job.getId()); } catch(RuntimeException e) { log.warn("report job {} failed", job.getId(), e); }
  }
 }
}
