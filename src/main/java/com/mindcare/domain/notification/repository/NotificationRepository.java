package com.mindcare.domain.notification.repository;

import com.mindcare.domain.notification.entity.Notification;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/**
 * 목록은 id(UUIDv7 = 생성 시각 순) 내림차순 키셋 페이지네이션. 인덱스 (user_id, created_at desc)와 같은 순서다.
 */
public interface NotificationRepository extends JpaRepository<Notification, UUID> {

    List<Notification> findByUserIdOrderByIdDesc(UUID userId, Limit limit);

    List<Notification> findByUserIdAndIdLessThanOrderByIdDesc(UUID userId, UUID before, Limit limit);

    List<Notification> findByUserIdAndReadAtIsNullOrderByIdDesc(UUID userId, Limit limit);

    List<Notification> findByUserIdAndReadAtIsNullAndIdLessThanOrderByIdDesc(UUID userId, UUID before, Limit limit);

    long countByUserIdAndReadAtIsNull(UUID userId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Notification n set n.readAt = :now where n.userId = :userId and n.readAt is null")
    int markAllRead(UUID userId, Instant now);
}
