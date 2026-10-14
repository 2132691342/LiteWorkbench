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
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;
import java.util.Objects;

/** 限流切面：固定窗口计数，窗口起点取 epoch 对齐桶 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    /** INCR 与首次 EXPIRE 必须同脚本原子完成，否则 expire 丢失会让窗口键无 TTL 永久生效 */
    private static final String INCR_WITH_TTL =
            "local count = redis.call('INCR', KEYS[1]) "
                    + "if count == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end return count";

    private final StringRedisTemplate stringRedisTemplate;

    /** 环绕拦截：窗口内计数超限即抛 RATE_LIMITED */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint point, RateLimit rateLimit) throws Throwable {
        String key = StrUtil.format(RedisKeyConst.RATE_LIMIT, rateLimit.prefix(), who(rateLimit),
                System.currentTimeMillis() / (rateLimit.windowSeconds() * 1000L));
        Long count = increment(key, rateLimit.windowSeconds() + 5L);
        if (Objects.nonNull(count) && count > rateLimit.limit()) {
            log.warn("[ratelimit.hit] key={} limit={}/{}s", key, rateLimit.limit(), rateLimit.windowSeconds());
            throw new BizException(ErrorCodeEnum.RATE_LIMITED);
        }
        return point.proceed();
    }

    /** 计数并保证首次写入带 TTL；Redis 不可用时放行而不是整站不可用 */
    private Long increment(String key, long ttlSeconds) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>(INCR_WITH_TTL, Long.class);
            return stringRedisTemplate.execute(script, List.of(key), String.valueOf(ttlSeconds));
        } catch (Exception e) {
            log.warn("[ratelimit.degraded] 计数失败，本次放行 key={} err={}", key, e.getMessage());
            return null;
        }
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
