package com.liteworkbench.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.common.PasswordUtil;
import com.liteworkbench.common.SecretCodec;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.AuthConst;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.constant.UserStateEnum;
import com.liteworkbench.domain.dto.LoginREQ;
import com.liteworkbench.domain.dto.PasswordREQ;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.domain.vo.LoginRESP;
import com.liteworkbench.domain.vo.PublicKeyVO;
import com.liteworkbench.domain.vo.UserVO;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 认证：公钥下发、登录、登出、当前用户组装、修改密码 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final MenuService menuService;
    private final UserService userService;
    private final SecretCodec secretCodec;
    private final StringRedisTemplate redisTemplate;

    /** 登录加密公钥，免登录可访问 */
    public PublicKeyVO publicKey() {
        PublicKeyVO vo = new PublicKeyVO();
        vo.setKeyId(secretCodec.keyId());
        vo.setAlgorithm("RSA/PKCS1Padding");
        vo.setPublicKey(secretCodec.publicKeyBase64());
        vo.setExpiresAt(secretCodec.expiresAt());
        return vo;
    }

    /** 登录：连续失败锁定 → 解密凭据 → 校验用户与密码 → 建立 Sa-Token 会话 */
    public LoginRESP login(LoginREQ req) {
        long start = System.currentTimeMillis();
        String failKey = StrUtil.format(RedisKeyConst.AUTH_FAIL, req.getUsername());
        if (currentFails(failKey) >= AuthConst.LOGIN_FAIL_LIMIT) {
            log.warn("[auth.login.locked] username={}", req.getUsername());
            throw new BizException(ErrorCodeEnum.ACCOUNT_LOCKED);
        }
        String password = secretCodec.decrypt(req.getKeyId(), req.getPassword());
        if (StrUtil.isBlank(password)) {
            markFail(failKey);
            throw new BizException(ErrorCodeEnum.CREDENTIAL_INVALID);
        }
        SysUserDO user = sysUserMapper.selectByUsername(req.getUsername());
        if (Objects.isNull(user)
                || !PasswordUtil.matches(password, user.getPasswordSalt(), user.getPasswordHash())) {
            markFail(failKey);
            log.warn("[auth.login.fail] username={} fails={}", req.getUsername(), currentFails(failKey));
            throw new BizException(ErrorCodeEnum.CREDENTIAL_INVALID);
        }
        if (!UserStateEnum.NORMAL.getCode().equals(user.getState())) {
            throw new BizException(ErrorCodeEnum.ACCOUNT_DISABLED);
        }
        clearFail(failKey);
        LoginUser loginUser = buildLoginUser(user);
        StpUtil.login(user.getUserId());
        StpUtil.getSession().set(AuthConst.SESSION_LOGIN_USER, loginUser);
        sysUserMapper.updateLastLogin(user.getUserId());
        LoginRESP resp = buildResp(loginUser, user);
        resp.setToken(StpUtil.getTokenValue());
        log.info("[auth.login] userId={} pwdResetRequired={} cost={}ms", user.getUserId(),
                resp.getPwdResetRequired(), System.currentTimeMillis() - start);
        return resp;
    }

    /** 登出：作废当前令牌 */
    public void logout() {
        String userId = StrUtil.EMPTY;
        try {
            userId = String.valueOf(StpUtil.getLoginIdDefaultNull());
            StpUtil.logout();
        } finally {
            log.info("[auth.logout] userId={}", userId);
        }
    }

    /** 当前用户：以会话快照为准，附带可见菜单树 */
    public LoginRESP current() {
        LoginUser loginUser = LoginContext.get();
        if (Objects.isNull(loginUser)) {
            throw new BizException(ErrorCodeEnum.UNAUTHORIZED);
        }
        SysUserDO user = sysUserMapper.selectById(loginUser.getUserId());
        return buildResp(loginUser, user);
    }

    /** 修改自己的密码：两段密文解密 → 强度校验 → 落库清标记 → 强制重登 */
    public void changePassword(PasswordREQ req) {
        String userId = LoginContext.userId();
        String oldPassword = requirePlain(req.getOldPassword(), req.getKeyId());
        String newPassword = requirePlain(req.getNewPassword(), req.getKeyId());
        userService.changePassword(userId, oldPassword, newPassword);
        log.info("[auth.password] userId={} 已改密，强制重新登录", userId);
        StpUtil.logout();
    }

    /** 解密失败统一转凭据无效，不区分"密文坏"与"密码错" */
    private String requirePlain(String cipher, String keyId) {
        String plain = secretCodec.decrypt(keyId, cipher);
        if (StrUtil.isBlank(plain)) {
            throw new BizException(ErrorCodeEnum.PAYLOAD_DECRYPT_FAILED);
        }
        return plain;
    }

    private long currentFails(String key) {
        try {
            String value = redisTemplate.opsForValue().get(key);
            return StrUtil.isBlank(value) ? 0L : NumberUtil.parseLong(value, 0L);
        } catch (Exception e) {
            log.warn("[auth.fail] 读取失败 {}", e.getMessage());
            return 0L;
        }
    }

    private void markFail(String key) {
        try {
            Long count = redisTemplate.opsForValue().increment(key);
            if (Objects.nonNull(count)) {
                redisTemplate.expire(key, Duration.ofSeconds(AuthConst.LOGIN_LOCK_SECONDS));
            }
        } catch (Exception e) {
            log.warn("[auth.fail] 计数失败 {}", e.getMessage());
        }
    }

    private void clearFail(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("[auth.fail] 清理失败 {}", e.getMessage());
        }
    }

    private LoginUser buildLoginUser(SysUserDO user) {
        List<SysRoleDO> roles = sysRoleMapper.selectByUserId(user.getUserId());
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getUserId());
        loginUser.setUsername(user.getUsername());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatarUrl(user.getAvatarUrl());
        loginUser.setRoleCodes(roles.stream().map(SysRoleDO::getRoleCode).toList());
        loginUser.setPerms(menuService.permsOf(user.getUserId()));
        loginUser.setPwdResetRequired(Boolean.TRUE.equals(user.getPwdResetRequired()));
        return loginUser;
    }

    private LoginRESP buildResp(LoginUser loginUser, SysUserDO user) {
        LoginRESP resp = new LoginRESP();
        resp.setUser(buildUserVO(loginUser, user));
        resp.setRoleCodes(loginUser.getRoleCodes());
        resp.setPerms(loginUser.getPerms());
        resp.setPwdResetRequired(Boolean.TRUE.equals(loginUser.getPwdResetRequired()));
        resp.setMenus(menuService.userTree(loginUser.getUserId()));
        return resp;
    }

    private UserVO buildUserVO(LoginUser loginUser, SysUserDO user) {
        UserVO vo = new UserVO();
        vo.setUserId(loginUser.getUserId());
        vo.setUsername(loginUser.getUsername());
        vo.setNickname(loginUser.getNickname());
        vo.setAvatarUrl(loginUser.getAvatarUrl());
        if (Objects.nonNull(user)) {
            vo.setEmail(user.getEmail());
            vo.setState(user.getState());
            vo.setLastLoginAt(TimeUtil.toMillis(user.getLastLoginAt()));
        }
        return vo;
    }
}
