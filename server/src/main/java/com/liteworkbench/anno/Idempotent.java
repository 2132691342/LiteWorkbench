package com.liteworkbench.anno;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 写接口幂等：同一用户 + 同参数在 TTL 内只允许提交一次 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 业务前缀，拼入 Redis 键 */
    String prefix();

    /** 占位有效期（秒），到期后允许再次提交 */
    int ttl() default 10;
}
