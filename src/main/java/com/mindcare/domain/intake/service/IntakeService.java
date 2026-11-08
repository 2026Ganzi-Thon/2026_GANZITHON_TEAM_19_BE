package com.mindcare.domain.intake.service;

import com.mindcare.domain.intake.dto.IntakeCreateRequest;
import com.mindcare.domain.intake.dto.IntakeListItemResponse;
import com.mindcare.domain.intake.dto.IntakeResponse;
import com.mindcare.domain.intake.entity.Intake;
import com.mindcare.domain.intake.entity.RiskLevel;
import com.mindcare.domain.intake.repository.IntakeRepository;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IntakeService {
    private final IntakeRepository intakeRepository;
    private final UserService userService;
    private final SensitiveTextCodec sensitiveTextCodec;

    @Transactional
    public IntakeResponse create(UUID clientId, IntakeCreateRequest request) {
        RiskLevel riskLevel = assessLocalRisk(request.text());
        Intake intake = intakeRepository.save(Intake.builder()
                .client(userService.getById(clientId)).category(request.category())
                .textEnc(sensitiveTextCodec.encrypt(request.text())).shareRaw(request.shareRaw())
                .preferredTimes(request.preferredTimes() == null ? List.of() : request.preferredTimes())
                .aiTags(Map.of("emotions", List.of(), "distortions", List.of(), "keywords", List.of()))
                .aiStatus("fallback").riskLevel(riskLevel).notifyWhenAvailable(request.notifyWhenAvailable()).build());
        return IntakeResponse.created(intake);
    }

    public List<IntakeListItemResponse> list(UUID clientId) {
        return intakeRepository.findByClientIdOrderByCreatedAtDesc(clientId).stream().map(IntakeListItemResponse::from).toList();
    }

    public List<IntakeResponse.Match> matches(UUID clientId, UUID intakeId) {
        Intake intake = intakeRepository.findById(intakeId).orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!intake.getClient().getId().equals(clientId)) throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        return List.of();
    }

    private RiskLevel assessLocalRisk(String text) {
        String normalized = text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.contains("자살") || normalized.contains("죽고 싶") || normalized.contains("목숨을 끊")) return RiskLevel.HIGH;
        if (normalized.contains("자해") || normalized.contains("해치고 싶")) return RiskLevel.MEDIUM;
        return RiskLevel.NONE;
    }
}
