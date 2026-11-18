package com.mindcare.domain.risk.service;

import com.mindcare.domain.risk.RiskAssessment;
import com.mindcare.domain.risk.entity.RiskEvent;
import com.mindcare.domain.risk.repository.RiskEventRepository;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RiskEventService {
    private final RiskEventRepository events;
    private final SensitiveTextCodec codec;

    @Transactional
    public void recordMessage(UUID sessionId, UUID messageId, UUID clientId, UUID providerId,
                              String body, RiskAssessment assessment) {
        if (!assessment.requiresAlert()) return;
        events.save(new RiskEvent("message", sessionId, messageId, clientId, providerId,
                assessment.level().getValue(), assessment.ruleHits(), codec.encrypt(body)));
    }
    public List<RiskEvent> listForProvider(UUID providerId) { return events.findByProviderIdOrderByCreatedAtDesc(providerId); }
    @Transactional
    public void acknowledge(UUID providerId, UUID eventId) {
        RiskEvent event = owned(providerId, eventId);
        try { event.acknowledge(providerId); } catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); }
    }
    @Transactional
    public void resolve(UUID providerId, UUID eventId, String note) {
        RiskEvent event = owned(providerId, eventId);
        try { event.resolve(providerId, codec.encrypt(note)); } catch (IllegalStateException e) { throw new AppException(ErrorCode.INVALID_STATE); }
    }
    private RiskEvent owned(UUID providerId, UUID eventId) {
        RiskEvent event = events.findById(eventId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!providerId.equals(event.getProviderId())) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        return event;
    }
}
