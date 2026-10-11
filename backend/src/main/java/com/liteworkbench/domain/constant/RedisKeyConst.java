package com.liteworkbench.domain.constant;

import cn.hutool.core.util.StrUtil;

/** Redis 键定义与拼装 */
public interface RedisKeyConst {

    /** 会话运行锁 */
    String LOCK_RUN = "lw:lock:run:{}";
    /** 供应方配置缓存 */
    String CACHE_PROVIDER = "lw:cache:provider:{}";
    /** 用户权限标识缓存 */
    String CACHE_PERM = "lw:cache:perm:{}";
    /** 幂等占位 */
    String IDEM = "lw:idem:{}:{}:{}";
    /** 限流计数，前缀 / 主体 / 窗口号 */
    String RATE_LIMIT = "lw:rl:{}:{}:{}";

    /** 用户权限标识缓存键 */
    static String cachePerm(String userId) {
        return StrUtil.format(CACHE_PERM, userId);
    }

    /** 会话运行锁键 */
    static String lockRun(String sessionId) {
        return StrUtil.format(LOCK_RUN, sessionId);
    }
}
