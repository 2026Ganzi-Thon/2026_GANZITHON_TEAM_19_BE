package com.mindcare.domain.user.service;

import com.mindcare.domain.user.dto.AdminUserRow;
import com.mindcare.domain.user.entity.ProviderProfile;
import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.ProviderProfileRepository;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.domain.user.repository.UserSearchRepository;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final UserSearchRepository userSearchRepository;
    private final ProviderProfileRepository providerProfileRepository;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    /** Firebase 계정은 있지만 가입 전이면 404 USER_NOT_REGISTERED (프론트가 온보딩으로 보냄). */
    public User getByFirebaseUid(String firebaseUid) {
        return userRepository.findByFirebaseUid(firebaseUid)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_REGISTERED));
    }

    public User getById(UUID id) {
        return userRepository.findById(id)
                .orElseThrow(() -> new AppException(ErrorCode.NOT_FOUND));
    }

    /** USR-02. 가입과 같은 닉네임 규칙, 남과 겹치면 409. 정지·심사 중에도 바꿀 수 있다 (탈퇴 계정은 불가). */
    @Transactional
    public User changeNickname(AuthUser auth, String requested) {
        if (auth.status() == UserStatus.WITHDRAWN) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        String nickname = NamePolicy.nickname(requested);
        User user = getById(auth.id());
        if (nickname.equals(user.getNickname())) {
            return user;
        }
        if (userRepository.existsByNickname(nickname)) {
            throw new AppException(ErrorCode.NICKNAME_TAKEN);
        }
        user.changeNickname(nickname);
        try {
            userRepository.flush();
        } catch (DataIntegrityViolationException e) {
            // 같은 닉네임을 동시에 고른 경우 (unique 제약)
            throw new AppException(ErrorCode.NICKNAME_TAKEN);
        }
        return user;
    }

    /** USR-07. 수련생만, 정지 중에도 가능 (TRD: trainee+suspended). 이미 완료했으면 그대로. */
    @Transactional
    public void completeEthicsTraining(AuthUser auth) {
        if (auth.role() != UserRole.TRAINEE) {
            throw new AppException(ErrorCode.FORBIDDEN_ROLE);
        }
        if (auth.status() != UserStatus.SUSPENDED) {
            auth.requireActive();
        }
        getById(auth.id()).completeEthicsTraining();
    }

    /** 알림 받을 어드민 전원 (CRD-04 제출 알림 등). */
    public List<UUID> listActiveAdminIds() {
        return userRepository.findIdsByRoleAndStatus(UserRole.ADMIN, UserStatus.ACTIVE);
    }

    /** CRD-04: 자격 제출 → 심사 대기. 정지·영구정지 계정은 여기까지 오기 전에 막힌다. */
    @Transactional
    public void markPendingReview(UUID userId) {
        getById(userId).markPendingReview();
    }

    /**
     * ADM-03 승인/반려. 심사 대기(pending_review)면 계정 상태를 바꾸고,
     * 이미 승인된(active) 상담사가 자격을 추가한 경우는 계정 상태를 그대로 둔다. 바뀐 상태를 돌려준다.
     */
    @Transactional
    public UserStatus decideReview(UUID userId, boolean approve) {
        User user = getById(userId);
        if (user.getStatus() == UserStatus.ACTIVE) {
            return user.getStatus();
        }
        if (user.getStatus() != UserStatus.PENDING_REVIEW) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", user.getStatus()));
        }
        if (approve) {
            user.approveReview();
        } else {
            user.rejectReview();
        }
        return user.getStatus();
    }

    // ---- 어드민 회원 관리 (ADM-04·05)

    /** 어드민이 손으로 바꿀 수 있는 상태. 심사(pending_review·rejected)와 탈퇴는 각자 흐름에서만 바뀐다. */
    private static final Set<UserStatus> ADMIN_SETTABLE =
            EnumSet.of(UserStatus.ACTIVE, UserStatus.SUSPENDED, UserStatus.BANNED, UserStatus.RESTRICTED);

    public List<AdminUserRow> searchForAdmin(String query, UserRole role, UserStatus status, UUID before, int limit) {
        return userSearchRepository.search(query, role, status, before, limit);
    }

    /**
     * ADM-05 상태 변경. active가 아닌 상태로 바꾸면 강제 로그아웃(tokens_revoked_at + 커밋 후 Firebase 폐기)도 같이 한다.
     * until은 suspended·restricted의 해제 시각 (없으면 어드민이 풀 때까지).
     */
    @Transactional
    public AdminUserRow changeStatusByAdmin(UUID adminId, UUID targetId, UserStatus next, Instant until) {
        User user = getManageable(adminId, targetId);
        if (!ADMIN_SETTABLE.contains(next)) {
            throw invalid("status", "not_allowed");
        }
        if (next == UserStatus.RESTRICTED && user.getRole() != UserRole.CLIENT) {
            throw invalid("status", "client_only");
        }
        // 심사 대기·반려 계정을 바로 active로 올리면 자격 심사를 건너뛰게 된다 (ADM-03으로)
        if (next == UserStatus.ACTIVE
                && (user.getStatus() == UserStatus.PENDING_REVIEW || user.getStatus() == UserStatus.REJECTED)) {
            throw new AppException(ErrorCode.INVALID_STATE,
                    Map.of("current", user.getStatus(), "reason", "credential_review_required"));
        }
        boolean timed = next == UserStatus.SUSPENDED || next == UserStatus.RESTRICTED;
        if (timed && until != null && !until.isAfter(clock.instant())) {
            throw invalid("until", "must_be_future");
        }
        user.changeStatusByAdmin(next, timed ? until : null);
        if (next != UserStatus.ACTIVE) {
            revoke(user);
        }
        return toAdminRow(user);
    }

    /** ADM-05 강제 로그아웃만 (상태는 그대로). */
    @Transactional
    public void revokeTokensByAdmin(UUID adminId, UUID targetId) {
        revoke(getManageable(adminId, targetId));
    }

    private User getManageable(UUID adminId, UUID targetId) {
        if (adminId.equals(targetId)) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("reason", "self"));
        }
        User user = getById(targetId);
        if (user.getRole() == UserRole.ADMIN) {
            throw new AppException(ErrorCode.FORBIDDEN_RESOURCE);
        }
        if (user.getStatus() == UserStatus.WITHDRAWN) {
            throw new AppException(ErrorCode.INVALID_STATE, Map.of("current", user.getStatus()));
        }
        return user;
    }

    private void revoke(User user) {
        user.revokeTokens(clock.instant());
        events.publishEvent(new TokenRevocation.Requested(user.getFirebaseUid()));
    }

    private AdminUserRow toAdminRow(User user) {
        String displayName = providerProfileRepository.findById(user.getId())
                .map(ProviderProfile::getDisplayName)
                .orElse(null);
        return new AdminUserRow(user.getId(), user.getRole(), user.getStatus(), user.getNickname(), displayName,
                user.getSuspendedUntil(), user.getRestrictedUntil(), user.getCreatedAt());
    }

    private static AppException invalid(String field, String reason) {
        return new AppException(ErrorCode.VALIDATION_ERROR,
                Map.of("fields", List.of(Map.of("field", field, "reason", reason))));
    }
}
