package com.liteworkbench.aspect;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.anno.RateLimit;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.domain.constant.RedisKeyConst;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Duration;
import java.util.Objects;

/** 限流切面：固定窗口计数，窗口起点取 epoch 对齐桶 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;

    /** 环绕拦截：窗口内计数超限即抛 RATE_LIMITED */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint point, RateLimit rateLimit) throws Throwable {
        String key = StrUtil.format(RedisKeyConst.RATE_LIMIT, rateLimit.prefix(), who(rateLimit),
                System.currentTimeMillis() / (rateLimit.windowSeconds() * 1000L));
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (Objects.equals(count, 1L)) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(rateLimit.windowSeconds() + 5L));
        }
        if (Objects.nonNull(count) && count > rateLimit.limit()) {
            log.warn("[ratelimit.hit] key={} limit={}/{}s", key, rateLimit.limit(), rateLimit.windowSeconds());
            throw new BizException(ErrorCodeEnum.RATE_LIMITED);
        }
        return point.proceed();
    }

    /** 计数主体：user 维度取当前用户，未登录回退 IP；ip 维度直接取客户端 IP */
    private String who(RateLimit rateLimit) {
        if (rateLimit.keyBy() == RateLimit.KeyBy.USER) {
            var user = LoginContext.get();
            if (Objects.nonNull(user)) {
                return user.getUserId();
            }
        }
        return clientIp();
    }

    /** 客户端 IP：优先代理头首段，兜底 remoteAddr */
    private String clientIp() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            HttpServletRequest request = attributes.getRequest();
            String forwarded = request.getHeader("X-Forwarded-For");
            if (StrUtil.isNotBlank(forwarded)) {
                return StrUtil.subBefore(forwarded, ',', false).trim();
            }
            return request.getRemoteAddr();
        }
        return "unknown";
    }
}
