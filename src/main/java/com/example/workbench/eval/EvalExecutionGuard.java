package com.example.workbench.eval;

import com.example.workbench.auth.AppUser;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * 限制单用户评测并发和单次规模，避免共享模型与检索资源被单个租户耗尽。
 */
@Component
public class EvalExecutionGuard {

    private final Set<Long> runningUsers = ConcurrentHashMap.newKeySet();
    private final int maxCasesPerRun;

    /**
     * 创建评测执行守卫。
     *
     * @param maxCasesPerRun 单次最多执行题数
     */
    public EvalExecutionGuard(@Value("${workbench.eval.run.max-cases:100}") int maxCasesPerRun) {
        this.maxCasesPerRun = Math.max(1, maxCasesPerRun);
    }

    /**
     * 在用户级单并发保护下执行一次评测。
     *
     * @param user 当前用户
     * @param caseCount 本次题数
     * @param action 评测操作
     * @param <T> 评测结果类型
     * @return 评测结果
     */
    public <T> T execute(AppUser user, int caseCount, Supplier<T> action) {
        if (caseCount > maxCasesPerRun) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "单次评测不能超过 " + maxCasesPerRun + " 条");
        }
        Long userId = user.getId();
        if (userId == null || !runningUsers.add(userId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前用户已有评测正在运行");
        }
        try {
            return action.get();
        } finally {
            runningUsers.remove(userId);
        }
    }
}
