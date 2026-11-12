package com.mindcare.domain.auth.service;

import com.mindcare.domain.auth.dto.RegisterRequest;
import com.mindcare.domain.consent.service.ConsentService;
import com.mindcare.domain.user.dto.MeResponse;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.domain.user.service.NamePolicy;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AUTH-01 가입. Firebase 로그인은 됐지만 users 행이 없는 사용자가 역할·닉네임·동의를 정해 가입한다.
 * 내담자는 바로 active, 수련생·전문가는 자격 심사 전까지 pending_review.
 */
@Service
@RequiredArgsConstructor
public class RegisterService {

    /** 가입할 때 반드시 동의해야 하는 4종 (TRD FR-ACC-02). 하나라도 없으면 422. */
    static final Set<String> REQUIRED_CONSENTS = Set.of("terms", "privacy", "sensitive", "age14");
    /** 가입 화면에서 받을 수 있는 동의. 리포트·대화록 전달 동의는 연계 단계(BE-2)에서 따로 받는다. */
    static final Set<String> SIGNUP_CONSENTS = Set.of(
            "terms", "privacy", "sensitive", "age14", "training_data", "marketing");

    private final UserRepository userRepository;
    private final ConsentService consentService;
    private final TermsCatalog termsCatalog;

    @Transactional
    public MeResponse register(String firebaseUid, RegisterRequest request) {
        if (request.role() == UserRole.ADMIN) {
            // 어드민은 운영진이 DB에서 직접 지정한다
            throw invalid("role", "not_allowed");
        }
        String nickname = NamePolicy.nickname(request.nickname());
        Map<String, String> consents = consents(request.consents());

        if (userRepository.existsByFirebaseUid(firebaseUid)) {
            throw new AppException(ErrorCode.ALREADY_REGISTERED);
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new AppException(ErrorCode.NICKNAME_TAKEN);
        }

        User user = User.builder()
                .firebaseUid(firebaseUid)
                .role(request.role())
                .nickname(nickname)
                .status(request.role() == UserRole.CLIENT ? UserStatus.ACTIVE : UserStatus.PENDING_REVIEW)
                // 동의 기록과 플래그가 어긋나지 않게, 해당 동의가 있을 때만 켠다
                .trainingDataOptIn(request.trainingDataOptInOrFalse() && consents.containsKey("training_data"))
                .marketingOptIn(request.marketingOptInOrFalse() && consents.containsKey("marketing"))
                .build();
        try {
            user = userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // 같은 계정으로 가입 요청이 동시에 두 번 오거나, 같은 닉네임을 동시에 고른 경우 (unique 제약)
            String cause = String.valueOf(e.getMostSpecificCause().getMessage());
            throw new AppException(cause.contains("nickname") ? ErrorCode.NICKNAME_TAKEN : ErrorCode.ALREADY_REGISTERED);
        }

        for (Map.Entry<String, String> consent : consents.entrySet()) {
            consentService.grant(user.getId(), consent.getKey(), consent.getValue(), null, null, null, null);
        }
        return MeResponse.from(user, null);
    }

    // 같은 type이 두 번 오면 마지막 것만. 모르는 type, 지금 약관과 다른 버전(AUTH-02), 필수 동의 누락은 422
    private Map<String, String> consents(List<RegisterRequest.ConsentItem> items) {
        Map<String, String> byType = new LinkedHashMap<>();
        for (RegisterRequest.ConsentItem item : items) {
            String type = item.type().strip();
            if (!SIGNUP_CONSENTS.contains(type)) {
                throw invalid("consents", "unknown_type");
            }
            String version = item.version().strip();
            String current = termsCatalog.currentVersion(type).orElseThrow();
            if (!current.equals(version)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, Map.of(
                        "fields", List.of(Map.of("field", "consents", "reason", "outdated_version")),
                        "type", type, "current_version", current));
            }
            byType.put(type, version);
        }
        if (!byType.keySet().containsAll(REQUIRED_CONSENTS)) {
            throw new AppException(ErrorCode.VALIDATION_ERROR, Map.of(
                    "fields", List.of(Map.of("field", "consents", "reason", "required_missing")),
                    "required", List.copyOf(REQUIRED_CONSENTS)));
        }
        return byType;
    }

    private static AppException invalid(String field, String reason) {
        return new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
