package com.mindcare.domain.report.repository;
import com.mindcare.domain.report.entity.Report;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReportRepository extends JpaRepository<Report, UUID> { Optional<Report> findBySessionId(UUID sessionId); }
