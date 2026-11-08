package com.mindcare.domain.consent.repository;
import com.mindcare.domain.consent.entity.Consent;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ConsentRepository extends JpaRepository<Consent,UUID>{}
