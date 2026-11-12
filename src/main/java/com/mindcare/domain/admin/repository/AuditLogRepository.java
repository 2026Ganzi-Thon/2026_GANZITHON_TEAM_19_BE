package com.mindcare.domain.admin.repository;

import com.mindcare.domain.admin.entity.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
