package com.liteworkbench.anno;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 接口限流：Redis 固定窗口计数，超限抛 RATE_LIMITED */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /** 限流键前缀，用于区分业务 */
    String prefix();

    /** 窗口内最大允许次数 */
    int limit();

    /** 窗口长度秒 */
    int windowSeconds() default 60;

    /** 计数维度：user 按当前用户，ip 按客户端 IP */
    KeyBy keyBy() default KeyBy.USER;

    enum KeyBy {
        USER, IP
    }
}
