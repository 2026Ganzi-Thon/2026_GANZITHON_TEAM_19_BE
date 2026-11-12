package com.mindcare.domain.notification.service;

import com.mindcare.domain.notification.entity.DeviceToken;
import com.mindcare.domain.notification.repository.DeviceTokenRepository;
import com.mindcare.domain.user.entity.UserStatus;
import com.mindcare.global.auth.AuthUser;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** TRD 13-1 USR-04. 로그인한 브라우저가 FCM 토큰을 받으면 등록, 로그아웃할 때 해제한다. 모든 역할·상태. */
@Service
@RequiredArgsConstructor
public class DeviceTokenService {

    private final DeviceTokenRepository deviceTokenRepository;

    /** upsert: 같은 토큰이 있으면 주인·시각만 갱신 (다른 계정으로 바뀐 브라우저면 주인을 옮긴다). */
    @Transactional
    public void register(AuthUser user, String token, String platform) {
        if (user.status() == UserStatus.WITHDRAWN) {
            throw new AppException(ErrorCode.UNAUTHENTICATED);
        }
        String trimmed = token.strip();
        deviceTokenRepository.findByToken(trimmed).ifPresentOrElse(
                existing -> existing.touch(user.id(), platform),
                () -> deviceTokenRepository.save(DeviceToken.of(user.id(), trimmed, platform)));
    }

    /** 내 토큰만 지운다. 없거나 남의 토큰이어도 204 (로그아웃이 실패하지 않게). */
    @Transactional
    public void unregister(AuthUser user, String token) {
        deviceTokenRepository.deleteMine(user.id(), token.strip());
    }
}
