package com.liteworkbench.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.common.PasswordUtil;
import com.liteworkbench.domain.constant.AuthConst;
import com.liteworkbench.domain.constant.UserStateEnum;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.domain.dto.LoginREQ;
import com.liteworkbench.domain.dto.PasswordREQ;
import com.liteworkbench.domain.vo.LoginRESP;
import com.liteworkbench.domain.vo.UserVO;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 认证：登录、登出、当前用户组装、修改密码 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final MenuService menuService;
    private final UserService userService;

    /** 登录：校验用户与密码后建立 Sa-Token 会话 */
    public LoginRESP login(LoginREQ req) {
        long start = System.currentTimeMillis();
        SysUserDO user = sysUserMapper.selectByUsername(req.getUsername());
        if (Objects.isNull(user)
                || !PasswordUtil.matches(req.getPassword(), user.getPasswordSalt(), user.getPasswordHash())) {
            log.warn("[auth.login.fail] username={}", req.getUsername());
            throw new BizException(ErrorCodeEnum.CREDENTIAL_INVALID);
        }
        if (!UserStateEnum.NORMAL.getCode().equals(user.getState())) {
            throw new BizException(ErrorCodeEnum.ACCOUNT_DISABLED);
        }
        LoginUser loginUser = buildLoginUser(user);
        StpUtil.login(user.getUserId());
        StpUtil.getSession().set(AuthConst.SESSION_LOGIN_USER, loginUser);
        sysUserMapper.updateLastLogin(user.getUserId());
        LoginRESP resp = buildResp(loginUser, user);
        resp.setToken(StpUtil.getTokenValue());
        log.info("[auth.login] userId={} cost={}ms", user.getUserId(), System.currentTimeMillis() - start);
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

    /** 修改自己的密码 */
    public void changePassword(PasswordREQ req) {
        userService.changePassword(LoginContext.userId(), req.getOldPassword(), req.getNewPassword());
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
        return loginUser;
    }

    private LoginRESP buildResp(LoginUser loginUser, SysUserDO user) {
        LoginRESP resp = new LoginRESP();
        resp.setUser(buildUserVO(loginUser, user));
        resp.setRoleCodes(loginUser.getRoleCodes());
        resp.setPerms(loginUser.getPerms());
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
            vo.setLastLoginAt(Objects.isNull(user.getLastLoginAt())
                    ? null : LocalDateTimeUtil.toEpochMilli(user.getLastLoginAt()));
        }
        return vo;
    }
}
