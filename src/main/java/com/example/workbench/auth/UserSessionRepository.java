package com.example.workbench.auth;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserSessionRepository extends JpaRepository<UserSession, Long> {

    Optional<UserSession> findByTokenHash(String tokenHash);

    void deleteByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from UserSession session where session.user.id = :userId and session.tokenHash <> :currentTokenHash")
    void deleteOtherSessions(@Param("userId") Long userId, @Param("currentTokenHash") String currentTokenHash);

    /**
     * 分批删除已过期的会话。
     *
     * <p>用子查询限流而不是一次性 {@code delete ... where expires_at < ?}：
     * 长期未清理时过期行可能很多，单条大事务会长时间持锁并放大 WAL。
     * 按 {@code expires_at} 升序取最早的一批，保证每轮都有进展。</p>
     *
     * @param cutoff 过期判定时间
     * @param limit  单批最多删除行数
     * @return 实际删除行数
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            delete from user_sessions
             where id in (
                   select id from user_sessions
                    where expires_at < :cutoff
                    order by expires_at
                    limit :limit)
            """, nativeQuery = true)
    int deleteExpiredBatch(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
