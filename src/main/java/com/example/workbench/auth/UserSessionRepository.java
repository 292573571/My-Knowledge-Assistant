package com.example.workbench.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    Optional<UserSession> findByLegacyToken(String legacyToken);

    void deleteByTokenHash(String tokenHash);

    void deleteByLegacyToken(String legacyToken);

    @Modifying
    @Query("delete from UserSession session where session.user.id = :userId and session.tokenHash <> :currentTokenHash")
    void deleteOtherSessions(@Param("userId") Long userId, @Param("currentTokenHash") String currentTokenHash);
}
