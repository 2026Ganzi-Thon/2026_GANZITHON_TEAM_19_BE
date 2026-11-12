package com.mindcare.domain.credential.repository;

import com.mindcare.domain.credential.entity.Credential;
import com.mindcare.domain.credential.entity.CredentialStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CredentialRepository extends JpaRepository<Credential, UUID> {

    List<Credential> findByUserIdOrderByCreatedAt(UUID userId);

    List<Credential> findByUserIdAndStatus(UUID userId, CredentialStatus status);

    /** ADM-01: 제출된 자격이 있는 사용자 id (오래 기다린 순). */
    @Query("""
            select c.userId from Credential c
             where c.status = com.mindcare.domain.credential.entity.CredentialStatus.SUBMITTED
             group by c.userId
             order by min(c.createdAt)""")
    List<UUID> findUserIdsWithSubmitted();
}
