package com.example.workbench.auth;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EmailVerificationSendRepository extends JpaRepository<EmailVerificationSend, Long> {

    @Query("select count(send) from EmailVerificationSend send where send.ipAddress = :ip and send.sentAt >= :since")
    long countRecentByIp(@Param("ip") String ip, @Param("since") Instant since);

    @Modifying
    @Transactional
    void deleteBySentAtBefore(Instant cutoff);
}
