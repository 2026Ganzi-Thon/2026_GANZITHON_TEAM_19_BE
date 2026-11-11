package com.mindcare.domain.job.repository;
import com.mindcare.domain.job.entity.*;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface JobRepository extends JpaRepository<Job,UUID>{ Optional<Job> findByDedupKeyAndStatusIn(String key, java.util.Collection<JobStatus> statuses); }
