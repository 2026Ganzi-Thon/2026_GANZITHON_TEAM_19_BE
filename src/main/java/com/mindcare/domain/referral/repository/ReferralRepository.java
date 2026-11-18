package com.mindcare.domain.referral.repository;
import com.mindcare.domain.referral.entity.Referral;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ReferralRepository extends JpaRepository<Referral,UUID>{ List<Referral> findByClientIdOrderByCreatedAtDesc(UUID clientId); }
