package com.mindcare.domain.notification.repository;

import com.mindcare.domain.notification.entity.DeviceToken;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface DeviceTokenRepository extends JpaRepository<DeviceToken, UUID> {

    Optional<DeviceToken> findByToken(String token);

    List<DeviceToken> findByUserId(UUID userId);

    @Modifying
    @Query("delete from DeviceToken d where d.userId = :userId and d.token = :token")
    int deleteMine(UUID userId, String token);

    @Modifying
    @Query("delete from DeviceToken d where d.token in :tokens")
    int deleteByTokens(Collection<String> tokens);
}
