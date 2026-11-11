package com.mindcare.domain.certificate.repository;
import com.mindcare.domain.certificate.entity.Certificate;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface CertificateRepository extends JpaRepository<Certificate,UUID>{ Optional<Certificate> findByCode(String code); }
