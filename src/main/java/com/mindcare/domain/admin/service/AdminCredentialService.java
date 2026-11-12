package com.mindcare.domain.admin.service;

import com.mindcare.domain.admin.dto.CredentialDecisionRequest;
import com.mindcare.domain.admin.dto.CredentialDecisionResponse;
import com.mindcare.domain.admin.dto.CredentialReviewDetail;
import com.mindcare.domain.admin.dto.CredentialReviewItem;
import com.mindcare.domain.credential.entity.Credential;
import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.CredentialStatus;
import com.mindcare.domain.credential.entity.VerifyResult;
import com.mindcare.domain.credential.service.CredentialService;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.domain.user.dto.ProviderProfileResponse;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.service.ProviderProfileService;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.response.PageResponse;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD 13-6 ADM-01~03 자격 심사. 어드민만.
 * 신청자 수가 적어서(간지톤 시연 규모) 목록은 한 번에 내려준다 (next_cursor = null).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AdminCredentialService {

    private final CredentialService credentialService;
    private final UserService userService;
    private final ProviderProfileService profileService;
    private final NotificationService notificationService;
    private final AuditLogService auditLogService;

    /** ADM-01. role·kind·verify_result로 거를 수 있다 (kind·verify_result는 제출된 자격 중 하나라도 맞으면). */
    public PageResponse<CredentialReviewItem> listPending(AuthUser admin, UserRole role, CredentialKind kind,
            VerifyResult verifyResult) {
        admin.requireRole(UserRole.ADMIN);
        List<CredentialReviewItem> items = credentialService.listUserIdsWithSubmitted().stream()
                .map(userId -> toItem(userService.getById(userId)))
                .filter(item -> role == null || item.role() == role)
                .filter(item -> kind == null || item.credentials().stream().anyMatch(c -> c.kind() == kind))
                .filter(item -> verifyResult == null
                        || item.credentials().stream().anyMatch(c -> c.verifyResult() == verifyResult))
                .toList();
        return PageResponse.last(items);
    }

    /** ADM-02. 실명·자격번호 원문을 보므로 열람 기록을 남긴다. */
    @Transactional
    public CredentialReviewDetail getDetail(AuthUser admin, UUID userId) {
        admin.requireRole(UserRole.ADMIN);
        User user = requireProvider(userId);
        ProviderProfileResponse profile = profileService.findMine(userId).orElse(null);
        List<CredentialReviewDetail.Item> credentials = credentialService.listByUser(userId).stream()
                .map(this::toDetailItem)
                .toList();
        auditLogService.record(admin.id(), "view_credential", "user", userId, null);
        return new CredentialReviewDetail(user.getId(), user.getRole(), user.getStatus(), user.getNickname(),
                profile, credentials);
    }

    /** ADM-03. 승인 → active, 반려 → rejected (사유 필수). 결과는 신청자에게 알림. */
    @Transactional
    public CredentialDecisionResponse decide(AuthUser admin, UUID userId, CredentialDecisionRequest request) {
        admin.requireRole(UserRole.ADMIN);
        requireProvider(userId);
        boolean approve = request.approve();
        String reason = request.reason() == null ? null : request.reason().strip();
        if (!approve && (reason == null || reason.isEmpty())) {
            throw new AppException(ErrorCode.VALIDATION_ERROR,
                    Map.of("fields", List.of(Map.of("field", "reason", "reason", "required_for_reject"))));
        }

        User provider = userService.getById(userId);
        int decided = credentialService.decideSubmitted(userId, admin.id(), approve, reason);
        UserStatus status = userService.decideReview(userId, approve);
        // TRD 17-3: type credential_decided, 제목 "심사 결과가 나왔어요", 링크는 상담사 대시보드 (수련생 /t, 전문가 /e)
        String dashboard = provider.getRole() == UserRole.TRAINEE ? "/t" : "/e";
        notificationService.notify(userId, "credential_decided", "심사 결과가 나왔어요",
                approve ? "자격 심사가 승인되었어요. 상담 가능 시간을 등록하면 매칭에 노출됩니다."
                        : "자격 심사가 반려되었어요. 사유를 확인하고 다시 제출해 주세요.",
                dashboard,
                approve ? Map.of("decision", "approve") : Map.of("decision", "reject", "reason", reason));
        auditLogService.record(admin.id(), approve ? "approve_credential" : "reject_credential", "user", userId,
                reason);
        return new CredentialDecisionResponse(userId, decided, status);
    }

    private User requireProvider(UUID userId) {
        User user = userService.getById(userId);
        if (user.getRole() != UserRole.TRAINEE && user.getRole() != UserRole.EXPERT) {
            throw new AppException(ErrorCode.NOT_FOUND);
        }
        return user;
    }

    private CredentialReviewItem toItem(User user) {
        List<Credential> submitted = credentialService.listByUser(user.getId()).stream()
                .filter(c -> c.getStatus() == CredentialStatus.SUBMITTED)
                .toList();
        Instant submittedAt = submitted.stream().map(Credential::getCreatedAt).filter(Objects::nonNull)
                .min(Comparator.naturalOrder()).orElse(null);
        String displayName = profileService.findMine(user.getId()).map(ProviderProfileResponse::displayName)
                .orElse(null);
        return new CredentialReviewItem(user.getId(), user.getRole(), user.getStatus(), user.getNickname(),
                displayName, submittedAt,
                submitted.stream()
                        .map(c -> new CredentialReviewItem.Summary(c.getKind(), c.getCertName(), c.getVerifyResult(),
                                c.isUserCorrected()))
                        .toList());
    }

    private CredentialReviewDetail.Item toDetailItem(Credential c) {
        return new CredentialReviewDetail.Item(c.getId(), c.getKind(), c.getCertName(),
                credentialService.decryptCertNumber(c), c.getIssuedOn(), credentialService.fileUrl(c),
                c.getOcrResult(), c.isUserCorrected(), c.getVerifyMethod(), c.getVerifyResult(), c.getVerifyRaw(),
                c.getStatus(), c.getRejectReason(), c.getReviewedAt(), c.getCreatedAt());
    }
}
