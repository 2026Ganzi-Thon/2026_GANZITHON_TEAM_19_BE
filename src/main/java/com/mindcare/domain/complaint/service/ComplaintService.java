package com.mindcare.domain.complaint.service;

import com.mindcare.domain.admin.service.AppConfigService;
import com.mindcare.domain.complaint.entity.Complaint;
import com.mindcare.domain.complaint.repository.ComplaintRepository;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComplaintService {
    private final ComplaintRepository complaints;
    private final AppConfigService appConfigService;

    @Transactional
    public Complaint receive(UUID sessionId, UUID reporterId, UUID targetId, String category, byte[] encryptedDescription) {
        if (!appConfigService.isEnabled("p1_complaints")) {
            throw new AppException(ErrorCode.FEATURE_DISABLED);
        }
        if (complaints.existsBySessionIdAndReporterId(sessionId, reporterId)) {
            throw new AppException(ErrorCode.COMPLAINT_EXISTS);
        }
        return complaints.save(Complaint.builder().sessionId(sessionId).reporterId(reporterId).targetId(targetId)
                .source("client").category(category).descriptionEnc(encryptedDescription).build());
    }

    @Transactional
    public void decide(UUID adminId, UUID id, String decision, String reason) {
        Complaint complaint = complaints.findById(id).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        try {
            complaint.beginReview();
            complaint.decide(decision, reason, adminId);
        } catch (IllegalStateException e) {
            throw new AppException(ErrorCode.INVALID_STATE);
        }
    }
}
