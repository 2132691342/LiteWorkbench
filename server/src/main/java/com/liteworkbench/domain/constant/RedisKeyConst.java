package com.liteworkbench.domain.constant;

import cn.hutool.core.util.StrUtil;

/** Redis 键定义与拼装 */
public interface RedisKeyConst {

    /** 运行中断标记 */
    String RUN_ABORT = "lw:run:%s:abort";
    /** 会话运行锁 */
    String LOCK_RUN = "lw:lock:run:%s";
    /** 供应方配置缓存 */
    String CACHE_PROVIDER = "lw:cache:provider:%s:%s";
    /** 用户权限标识缓存 */
    String CACHE_PERM = "lw:cache:perm:%s";
    /** 幂等占位 */
    String IDEM = "lw:idem:%s:%s:%s";

    /** 用户权限标识缓存键 */
    static String cachePerm(String userId) {
        return StrUtil.format(CACHE_PERM, userId);
    }

    /** 幂等占位键：前缀 + 用户 + 参数摘要 */
    static String idem(String prefix, String userId, String digest) {
        return StrUtil.format(IDEM, prefix, userId, digest);
    }

    /** 运行中断标记键 */
    static String runAbort(String runId) {
        return StrUtil.format(RUN_ABORT, runId);
    }

    /** 会话运行锁键 */
    static String lockRun(String sessionId) {
        return StrUtil.format(LOCK_RUN, sessionId);
    }

    /** 供应方配置缓存键 */
    static String cacheProvider(String providerCode) {
        return StrUtil.format(CACHE_PROVIDER, providerCode);
    }
}
