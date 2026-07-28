package com.example.workbench.auth;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByToken(String token);

    void deleteByToken(String token);

    @Modifying
    @Query("delete from UserSession session where session.user.id = :userId and session.token <> :currentToken")
    void deleteOtherSessions(@Param("userId") Long userId, @Param("currentToken") String currentToken);
}
