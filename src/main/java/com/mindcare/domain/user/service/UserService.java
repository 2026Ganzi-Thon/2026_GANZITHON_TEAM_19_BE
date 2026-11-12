package com.mindcare.domain.user.service;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;

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
}
