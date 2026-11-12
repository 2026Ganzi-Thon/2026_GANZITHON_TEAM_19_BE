package com.mindcare.domain.user.service;

import com.mindcare.domain.user.dto.ProviderProfileRequest;
import com.mindcare.domain.user.dto.ProviderProfileResponse;
import com.mindcare.domain.user.dto.ProviderPublicResponse;
import com.mindcare.domain.user.entity.Career;
import com.mindcare.domain.user.entity.ProviderProfile;
import com.mindcare.domain.user.entity.ProviderTrack;
import com.mindcare.domain.user.entity.ServiceLabel;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.ProviderProfileRepository;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProviderProfileService {

    private final ProviderProfileRepository profileRepository;
    private final UserService userService;
    private final SensitiveTextCodec codec;

    /** 다른 도메인용: 프로필이 있는지 (CRD-04 최종 제출 전 확인). */
    public boolean exists(UUID userId) {
        return profileRepository.existsById(userId);
    }

    /** 자격 진위 확인용 실명 (CRD-02). 프로필이 없으면 비어 있다. */
    public Optional<String> findRealName(UUID userId) {
        return profileRepository.findById(userId).map(profile -> codec.decrypt(profile.getRealNameEnc()));
    }

    /** USR-01의 provider_profile. 내담자·프로필 미작성이면 비어 있다. */
    public Optional<ProviderProfileResponse> findMine(UUID userId) {
        return profileRepository.findById(userId).map(this::toMine);
    }

    /**
     * USR-06. 수련생·전문가가 심사 전(pending_review)부터 쓴다. 반려(rejected) 뒤 다시 고쳐 내는 경우도 허용한다.
     */
    @Transactional
    public ProviderProfileResponse upsert(AuthUser user, ProviderProfileRequest request) {
        requireProviderEditable(user);
        ProviderTrack track = ProviderTrack.of(user.role());

        String displayName = NamePolicy.displayName(request.displayName());
        if (profileRepository.existsByDisplayNameAndUserIdNot(displayName, user.id())) {
            throw displayNameTaken();
        }
        ServiceLabel serviceLabel = serviceLabel(track, request.serviceLabel());
        List<String> specialties = specialties(request.specialties());
        String realName = required("real_name", NamePolicy.normalize(request.realName()));
        String school = blankToNull(NamePolicy.normalize(request.school()));
        String bio = request.bio() == null ? "" : NamePolicy.normalize(request.bio());
        List<Career> career = request.career() == null ? List.of() : request.career().stream()
                .map(item -> new Career(NamePolicy.normalize(item.org()), item.from(), item.to()))
                .toList();

        ProviderProfile profile = profileRepository.findById(user.id())
                .orElseGet(() -> ProviderProfile.create(user.id(), track));
        profile.update(displayName, codec.encrypt(realName), serviceLabel, specialties, bio, career,
                school == null ? null : codec.encrypt(school),
                request.acceptsSupervision() == null || request.acceptsSupervision());
        try {
            return toMine(profileRepository.saveAndFlush(profile));
        } catch (DataIntegrityViolationException e) {
            // 동시에 같은 활동명을 저장한 경우 (unique 제약)
            throw displayNameTaken();
        }
    }

    /** USR-05. 내담자만, 승인(active)된 상담사만 보인다. 심사 중·정지 상담사는 404. */
    public ProviderPublicResponse getPublic(AuthUser user, UUID providerId) {
        user.requireRole(UserRole.CLIENT);
        ProviderProfile profile = profileRepository.findById(providerId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        User provider = userService.getById(providerId);
        if (provider.getStatus() != UserStatus.ACTIVE) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return new ProviderPublicResponse(
                profile.getUserId(),
                profile.getDisplayName(),
                profile.getTrack(),
                profile.getServiceLabel(),
                profileRepository.findApprovedCertNames(providerId),
                profile.getSpecialties(),
                profile.getBio(),
                profile.getCareer(),
                profile.getRatingAvg(),
                profile.getRatingCount());
    }

    private static void requireProviderEditable(AuthUser user) {
        if (user.role() != UserRole.TRAINEE && user.role() != UserRole.EXPERT) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        if (user.status() != UserStatus.PENDING_REVIEW && user.status() != UserStatus.REJECTED) {
            user.requireActive();
        }
    }

    // TRD: 수련생은 service_label 필수, 전문가는 쓰지 않는다 (보내도 무시)
    private static ServiceLabel serviceLabel(ProviderTrack track, ServiceLabel requested) {
        if (track == ProviderTrack.EXPERT) {
            return null;
        }
        if (requested == null) {
            throw NamePolicy.invalid("service_label", "not_null");
        }
        return requested;
    }

    // 최대 5개(@Size), 사전에 있는 값만, 중복은 하나로
    private static List<String> specialties(List<String> requested) {
        List<String> normalized = List.copyOf(new LinkedHashSet<>(
                requested.stream().map(NamePolicy::normalize).toList()));
        for (String tag : normalized) {
            if (!SpecialtyTags.contains(tag)) {
                throw new AppException(ErrorCode.VALIDATION_ERROR, Map.of(
                        "fields", List.of(Map.of("field", "specialties", "reason", "unknown_tag")),
                        "allowed", SpecialtyTags.ALL));
            }
        }
        return normalized;
    }

    private static String required(String field, String value) {
        if (value == null || value.isEmpty()) {
            throw NamePolicy.invalid(field, "not_blank");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static AppException displayNameTaken() {
        return new AppException(ErrorCode.NICKNAME_TAKEN, "이미 사용 중인 활동명입니다", Map.of("field", "display_name"));
    }

    private ProviderProfileResponse toMine(ProviderProfile profile) {
        return new ProviderProfileResponse(
                profile.getTrack(),
                profile.getDisplayName(),
                codec.decrypt(profile.getRealNameEnc()),
                profile.getServiceLabel(),
                profile.getSpecialties(),
                profile.getBio(),
                profile.getCareer(),
                profile.getSchoolEnc() == null ? null : codec.decrypt(profile.getSchoolEnc()),
                profile.isAcceptsSupervision());
    }
}
