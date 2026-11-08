package com.mindcare.domain.complaint.repository;
import com.mindcare.domain.complaint.entity.Complaint;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ComplaintRepository extends JpaRepository<Complaint,UUID>{ boolean existsBySessionIdAndReporterId(UUID sessionId, UUID reporterId); }
