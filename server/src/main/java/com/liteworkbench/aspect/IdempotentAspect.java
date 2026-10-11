package com.liteworkbench.aspect;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.domain.constant.RedisKeyConst;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 幂等切面：SETNX 占位，命中即抛 REPEAT_SUBMIT */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class IdempotentAspect {

    private static final String PLACEHOLDER = "1";

    private final StringRedisTemplate stringRedisTemplate;

    /** 环绕拦截：占位成功才放行，异常时保留占位由 TTL 自动释放 */
    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint point, Idempotent idempotent) throws Throwable {
        String userId = LoginContext.userId();
        String digest = digestArgs(point.getArgs());
        String key = RedisKeyConst.idem(idempotent.prefix(), userId, digest);
        Boolean acquired = stringRedisTemplate.opsForValue()
                .setIfAbsent(key, PLACEHOLDER, Duration.ofSeconds(idempotent.ttl()));
        if (!Boolean.TRUE.equals(acquired)) {
            log.warn("[idem.repeat] prefix={} userId={} method={}", idempotent.prefix(), userId,
                    point.getSignature().getName());
            throw new BizException(ErrorCodeEnum.REPEAT_SUBMIT);
        }
        return point.proceed();
    }

    /** 业务参数摘要：剔除 Servlet 上下文对象后取 MD5 */
    private String digestArgs(Object[] args) {
        List<Object> businessArgs = Arrays.stream(args).filter(this::isBusinessArg).toList();
        return DigestUtil.md5Hex(JSONUtil.toJsonStr(businessArgs));
    }

    private boolean isBusinessArg(Object arg) {
        return Objects.nonNull(arg)
                && !(arg instanceof HttpServletRequest)
                && !(arg instanceof HttpServletResponse);
    }
}
