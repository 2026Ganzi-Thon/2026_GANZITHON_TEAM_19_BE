package com.mindcare.domain.job.service;
import com.mindcare.domain.job.entity.Job; import com.mindcare.domain.job.repository.JobRepository; import java.util.UUID; import lombok.RequiredArgsConstructor; import org.springframework.stereotype.Service; import org.springframework.transaction.annotation.Transactional;
/** Transactional state transitions used by report/AI workers. External AI calls run outside this transaction. */
@Service @RequiredArgsConstructor public class JobWorker { private final JobRepository jobs;
 @Transactional public Job claim(UUID jobId) { Job job=jobs.findById(jobId).orElseThrow(); job.start(); return job; }
 @Transactional public void succeed(UUID jobId) { jobs.findById(jobId).orElseThrow().succeed(); }
 @Transactional public void fail(UUID jobId) { jobs.findById(jobId).orElseThrow().fail(); }
}
