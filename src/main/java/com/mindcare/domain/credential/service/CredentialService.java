package com.mindcare.domain.credential.service;

import com.mindcare.domain.credential.dto.CredentialCreateRequest;
import com.mindcare.domain.credential.dto.CredentialResponse;
import com.mindcare.domain.credential.dto.CredentialUpdateRequest;
import com.mindcare.domain.credential.dto.SubmitResponse;
import com.mindcare.domain.credential.dto.UploadUrlRequest;
import com.mindcare.domain.credential.dto.UploadUrlResponse;
import com.mindcare.domain.credential.entity.Credential;
import com.mindcare.domain.credential.entity.CredentialKind;
import com.mindcare.domain.credential.entity.CredentialStatus;
import com.mindcare.domain.credential.repository.CredentialRepository;
import com.mindcare.domain.notification.service.NotificationService;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.service.NamePolicy;
import com.mindcare.domain.user.service.ProviderProfileService;
import com.mindcare.domain.user.service.UserService;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.crypto.SensitiveTextCodec;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import com.mindcare.global.storage.Storage;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * TRD 13-2 CRD-01~05. 수련생·전문가가 자격 서류를 올리고 심사를 요청한다.
 * 심사 전(pending_review)·반려(rejected)·승인 후(active, 자격 추가) 모두 쓸 수 있다.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CredentialService {

    static final Map<String, String> ALLOWED_TYPES = Map.of(
            "application/pdf", "pdf", "image/jpeg", "jpg", "image/png", "png");
    private static final Set<CredentialKind> TRAINEE_KINDS = Set.of(CredentialKind.ENROLLMENT, CredentialKind.COMPLETION);
    private static final Set<CredentialKind> EXPERT_KINDS = Set.of(CredentialKind.NATIONAL, CredentialKind.SOCIETY);

    private final CredentialRepository credentialRepository;
    private final CredentialVerifier verifier;
    private final Storage storage;
    private final SensitiveTextCodec codec;
    private final UserService userService;
    private final ProviderProfileService profileService;
    private final NotificationService notificationService;

    /** CRD-01. key는 사용자별 폴더라 다른 사람 파일 key를 CRD-02에 넣어도 거절된다. */
    public UploadUrlResponse issueUploadUrl(AuthUser user, UploadUrlRequest request) {
        requireProvider(user);
        String extension = ALLOWED_TYPES.get(request.contentType());
        if (extension == null) {
            throw invalid("content_type", "unsupported_type");
        }
        String key = keyPrefix(user.id()) + UUID.randomUUID() + "." + extension;
        String url = storage.presignPut(key, request.contentType(), request.size());
        return new UploadUrlResponse(url, key, Storage.UPLOAD_EXPIRES.toSeconds());
    }

    /** CRD-02. 국가자격 + verify=true면 진위 확인 어댑터를 바로 부른다. 학회 서류 OCR(AI-07)은 AI 서버 연동 후. */
    @Transactional
    public CredentialResponse create(AuthUser user, CredentialCreateRequest request) {
        requireProvider(user);
        requireKindForRole(user.role(), request.kind());
        boolean verify = Boolean.TRUE.equals(request.verify());
        if (verify && request.kind() != CredentialKind.NATIONAL) {
            throw invalid("verify", "national_only");
        }
        String certNumber = blankToNull(NamePolicy.normalize(request.certNumber()));
        if (verify && certNumber == null) {
            throw invalid("cert_number", "required_for_verify");
        }
        String fileKey = blankToNull(request.fileKey());
        if (fileKey == null && !verify) {
            throw invalid("file_key", "required");
        }
        if (fileKey != null) {
            requireUploadedFile(user.id(), fileKey);
        }

        Credential credential = Credential.draft(user.id(), request.kind(),
                NamePolicy.normalize(request.certName()),
                certNumber == null ? null : codec.encrypt(certNumber),
                request.issuedOn(), fileKey);
        if (verify) {
            String realName = profileService.findRealName(user.id()).orElse(null);
            CredentialVerifier.Result result = verifier.verify(credential.getCertName(), certNumber, realName,
                    request.issuedOn());
            credential.recordVerification(result.result(), result.raw());
        }
        return toResponse(credentialRepository.save(credential));
    }

    /** CRD-03. 내 자격 목록 (반려 사유 포함). */
    public List<CredentialResponse> listMine(AuthUser user) {
        requireProvider(user);
        return credentialRepository.findByUserIdOrderByCreatedAt(user.id()).stream()
                .map(this::toResponse)
                .toList();
    }

    /** CRD-05. draft·rejected만 고칠 수 있다. */
    @Transactional
    public CredentialResponse update(AuthUser user, UUID credentialId, CredentialUpdateRequest request) {
        requireProvider(user);
        Credential credential = credentialRepository.findById(credentialId)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
        if (!credential.getUserId().equals(user.id())) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        if (!credential.isEditable()) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", credential.getStatus()));
        }
        String certNumber = blankToNull(NamePolicy.normalize(request.certNumber()));
        credential.correct(
                blankToNull(NamePolicy.normalize(request.certName())),
                certNumber == null ? null : codec.encrypt(certNumber), certNumber != null,
                request.issuedOn(), request.issuedOn() != null);
        return toResponse(credential);
    }

    /**
     * CRD-04. draft 전부 submitted, 계정은 심사 대기로 바꾸고 어드민에게 알린다.
     * 이미 승인(active)된 상담사가 자격을 추가하는 경우에는 매칭에서 빠지지 않게 계정 상태를 그대로 둔다.
     */
    @Transactional
    public SubmitResponse submit(AuthUser user) {
        requireProvider(user);
        if (!profileService.exists(user.id())) {
            throw new AppException(ErrorCode.INVALID_STATE, "프로필을 먼저 작성해 주세요", Map.of("reason", "profile_required"));
        }
        List<Credential> drafts = credentialRepository.findByUserIdAndStatus(user.id(), CredentialStatus.DRAFT);
        if (drafts.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_STATE, "제출할 자격이 없습니다", Map.of("reason", "no_draft"));
        }
        drafts.forEach(Credential::submit);

        UserStatus status = user.status();
        if (status != UserStatus.ACTIVE) {
            userService.markPendingReview(user.id());
            status = UserStatus.PENDING_REVIEW;
        }
        notificationService.notifyAll(userService.listActiveAdminIds(), "credential_submitted",
                "새 자격 심사 요청", "자격 서류 " + drafts.size() + "건이 제출되었습니다.",
                "/admin/credentials/" + user.id(), Map.of("user_id", user.id().toString()));
        return new SubmitResponse(drafts.size(), status);
    }

    // ---- 어드민 심사 (ADM-01~03). 권한 확인은 admin 도메인 service에서 한다

    /** ADM-01: 제출된 자격이 있는 사용자 (오래 기다린 순). */
    public List<UUID> listUserIdsWithSubmitted() {
        return credentialRepository.findUserIdsWithSubmitted();
    }

    public List<Credential> listByUser(UUID userId) {
        return credentialRepository.findByUserIdOrderByCreatedAt(userId);
    }

    /** ADM-03: 제출된(submitted) 자격을 한꺼번에 승인/반려한다. 처리한 건수를 돌려준다. */
    @Transactional
    public int decideSubmitted(UUID userId, UUID adminId, boolean approve, String reason) {
        List<Credential> submitted = credentialRepository.findByUserIdAndStatus(userId, CredentialStatus.SUBMITTED);
        if (submitted.isEmpty()) {
            throw new AppException(ErrorCode.INVALID_STATE, "심사할 자격이 없습니다", Map.of("reason", "no_submitted"));
        }
        for (Credential credential : submitted) {
            if (approve) {
                credential.approve(adminId);
            } else {
                credential.reject(adminId, reason);
            }
        }
        return submitted.size();
    }

    /** 서류 보기 URL (10분). 파일이 없으면 null. */
    public String fileUrl(Credential credential) {
        return credential.getFileKey() == null ? null : storage.presignGet(credential.getFileKey());
    }

    /** 어드민 화면용 (ADM-02): 자격번호 원문. */
    public String decryptCertNumber(Credential credential) {
        return credential.getCertNumberEnc() == null ? null : codec.decrypt(credential.getCertNumberEnc());
    }

    static String keyPrefix(UUID userId) {
        return "credentials/" + userId + "/";
    }

    // 수련생·전문가만, 정지·영구정지·탈퇴는 불가 (심사 중·반려는 허용)
    private static void requireProvider(AuthUser user) {
        if (user.role() != UserRole.TRAINEE && user.role() != UserRole.EXPERT) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        if (user.status() != UserStatus.PENDING_REVIEW && user.status() != UserStatus.REJECTED) {
            user.requireActive();
        }
    }

    private static void requireKindForRole(UserRole role, CredentialKind kind) {
        Set<CredentialKind> allowed = role == UserRole.TRAINEE ? TRAINEE_KINDS : EXPERT_KINDS;
        if (!allowed.contains(kind)) {
            throw invalid("kind", "not_allowed_for_role");
        }
    }

    // 본인 폴더의 key이고, 실제로 올라왔고, 형식·크기가 맞아야 한다
    private void requireUploadedFile(UUID userId, String fileKey) {
        if (!fileKey.startsWith(keyPrefix(userId)) || fileKey.contains("..")) {
            throw invalid("file_key", "invalid");
        }
        Storage.ObjectInfo info = storage.head(fileKey)
                .orElseThrow(() -> invalid("file_key", "not_uploaded"));
        if (!ALLOWED_TYPES.containsKey(info.contentType()) || info.size() > 10L * 1024 * 1024) {
            throw invalid("file_key", "unsupported_type");
        }
    }

    private CredentialResponse toResponse(Credential credential) {
        return CredentialResponse.of(credential, decryptCertNumber(credential));
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    /** 422 VALIDATION_ERROR {fields:[{field, reason}]} (NamePolicy와 같은 형식). */
    private static AppException invalid(String field, String reason) {
        return new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
