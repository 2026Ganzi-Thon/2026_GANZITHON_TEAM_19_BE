package com.mindcare.domain.user.service;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.repository.UserRepository;
import com.mindcare.global.error.AppException;
import com.mindcare.global.error.ErrorCode;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
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
}
