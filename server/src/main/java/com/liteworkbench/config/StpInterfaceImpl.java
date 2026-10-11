package com.liteworkbench.config;

import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.collection.CollUtil;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.domain.constant.AuthConst;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.mapper.SysMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Sa-Token 权限数据源：角色取会话快照，权限标识走 Redis 缓存回源数据库 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    /** 空权限占位，避免缓存穿透 */
    private static final String EMPTY_FLAG = "__EMPTY__";

    private final StringRedisTemplate stringRedisTemplate;
    private final SysMenuMapper sysMenuMapper;
    private final AuthProperties authProperties;

    /** 返回用户权限标识集合，命中缓存直接返回 */
    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        String userId = String.valueOf(loginId);
        String cacheKey = RedisKeyConst.cachePerm(userId);
        List<String> cached = stringRedisTemplate.opsForList().range(cacheKey, 0, -1);
        if (CollUtil.isNotEmpty(cached)) {
            if (cached.size() == 1 && EMPTY_FLAG.equals(cached.get(0))) {
                return CollUtil.newArrayList();
            }
            return cached;
        }
        List<String> perms = sysMenuMapper.selectPermCodesByUserId(userId);
        if (CollUtil.isEmpty(perms)) {
            stringRedisTemplate.opsForList().rightPush(cacheKey, EMPTY_FLAG);
        } else {
            stringRedisTemplate.opsForList().rightPushAll(cacheKey, perms);
        }
        stringRedisTemplate.expire(cacheKey, Duration.ofSeconds(authProperties.getPermCacheSeconds()));
        return Objects.isNull(perms) ? CollUtil.newArrayList() : perms;
    }

    /** 返回用户角色码集合，取会话中的登录快照 */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        LoginUser user = loadLoginUser(loginId);
        return Objects.isNull(user) ? CollUtil.newArrayList() : user.getRoleCodes();
    }

    /** 清除指定用户的权限缓存，授权变更后调用 */
    public void clearPermCache(String userId) {
        stringRedisTemplate.delete(RedisKeyConst.cachePerm(userId));
    }

    private LoginUser loadLoginUser(Object loginId) {
        try {
            Object value = StpUtil.getSessionByLoginId(loginId, false).get(AuthConst.SESSION_LOGIN_USER);
            return value instanceof LoginUser user ? user : null;
        } catch (Exception e) {
            log.warn("[perm.session.miss] loginId={} message={}", loginId, e.getMessage());
            return null;
        }
    }
}
