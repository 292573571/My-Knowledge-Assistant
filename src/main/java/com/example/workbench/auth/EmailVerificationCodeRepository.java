package com.example.workbench.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;

public interface EmailVerificationCodeRepository extends JpaRepository<EmailVerificationCode, Long> {

    Optional<EmailVerificationCode> findByEmail(String email);

    void deleteByEmail(String email);

    @Modifying
    void deleteByExpiresAtBefore(java.time.Instant cutoff);
}
