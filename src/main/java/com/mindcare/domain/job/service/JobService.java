package com.mindcare.domain.job.service;
import com.mindcare.domain.job.entity.*;
import com.mindcare.domain.job.repository.JobRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
@Service @RequiredArgsConstructor @Transactional
public class JobService {
 private final JobRepository jobs;
 public Job enqueue(String type,String dedupKey,Map<String,String> payload){return jobs.findByDedupKeyAndStatusIn(dedupKey,List.of(JobStatus.QUEUED,JobStatus.RUNNING)).orElseGet(()->jobs.save(new Job(type,dedupKey,payload)));}
}
