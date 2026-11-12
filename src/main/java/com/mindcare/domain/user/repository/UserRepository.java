package com.mindcare.domain.user.repository;

import com.mindcare.domain.user.entity.User;
import com.mindcare.domain.user.entity.UserRole;
import com.mindcare.domain.user.entity.UserStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface UserRepository extends JpaRepository<User, UUID> {

    Optional<User> findByFirebaseUid(String firebaseUid);

    boolean existsByFirebaseUid(String firebaseUid);

    boolean existsByNickname(String nickname);

    @Query("select u.id from User u where u.role = :role and u.status = :status")
    List<UUID> findIdsByRoleAndStatus(UserRole role, UserStatus status);
}
