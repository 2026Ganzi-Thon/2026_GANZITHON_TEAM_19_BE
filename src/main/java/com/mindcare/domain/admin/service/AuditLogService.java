package com.mindcare.domain.admin.service;

import com.mindcare.domain.admin.entity.AuditLog;
import com.mindcare.domain.admin.repository.AuditLogRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 감사 로그. 다른 도메인(BE-2 리포트 열람 등)도 이 서비스로 남긴다. */
@Service
@RequiredArgsConstructor
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;

    @Transactional
    public void record(UUID actorId, String action, String targetType, UUID targetId, String reason) {
        auditLogRepository.save(AuditLog.of(actorId, action, targetType, targetId, reason));
    }
}
