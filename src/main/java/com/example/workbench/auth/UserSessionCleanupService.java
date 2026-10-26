package com.example.workbench.auth;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 清理已过期的登录会话。
 *
 * <p>过期会话此前只有用户主动登出时才会删除，未登出的会话行会永久留在表里：
 * 行数只增不减，唯一索引随之变大，登录写入与过期判定的成本一起上升。
 * 这里按批删除，避免长期未清理后一次性大事务锁表。</p>
 */
@Service
public class UserSessionCleanupService {

    private static final Logger log = LoggerFactory.getLogger(UserSessionCleanupService.class);
    private static final int BATCH_SIZE = 1000;
    /** 单轮最多执行的批次数，避免积压很多时把一次调度拖成长任务。 */
    private static final int MAX_BATCHES = 10;

    private final UserSessionRepository sessionRepository;

    public UserSessionCleanupService(UserSessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /**
     * 删除过期会话。
     *
     * @param now 当前时间
     * @return 本轮删除的会话行数
     */
    public int cleanupExpired(Instant now) {
        int total = 0;
        for (int round = 0; round < MAX_BATCHES; round++) {
            int deleted = sessionRepository.deleteExpiredBatch(now, BATCH_SIZE);
            total += deleted;
            if (deleted < BATCH_SIZE) {
                break;
            }
        }
        if (total > 0) {
            log.info("Expired user sessions purged count={}", total);
        }
        return total;
    }
}
