package com.liteworkbench.service;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.liteworkbench.common.LoginUser;
import com.liteworkbench.domain.constant.AuthConst;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 登录快照装配：从数据库组装角色码与权限标识，授权变更后刷新会话快照 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LoginUserAssembler {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final MenuService menuService;

    /** 由用户实体组装登录快照：角色码取角色表，权限标识取菜单授权 */
    public LoginUser assemble(SysUserDO user) {
        List<SysRoleDO> roles = sysRoleMapper.selectByUserId(user.getUserId());
        LoginUser loginUser = new LoginUser();
        loginUser.setUserId(user.getUserId());
        loginUser.setUsername(user.getUsername());
        loginUser.setNickname(user.getNickname());
        loginUser.setAvatarUrl(user.getAvatarUrl());
        loginUser.setRoleCodes(roles.stream().map(SysRoleDO::getRoleCode).toList());
        loginUser.setPerms(menuService.permsOf(user.getUserId()));
        loginUser.setState(user.getState());
        loginUser.setPwdResetRequired(Boolean.TRUE.equals(user.getPwdResetRequired()));
        return loginUser;
    }

    /** 刷新目标用户的登录会话快照：授权变更后 /auth/current 即时生效，用户不在线则跳过 */
    public void refreshSession(String userId) {
        SysUserDO user = sysUserMapper.selectById(userId);
        if (Objects.isNull(user)) {
            return;
        }
        try {
            SaSession session = StpUtil.getSessionByLoginId(userId, false);
            if (Objects.nonNull(session)) {
                session.set(AuthConst.SESSION_LOGIN_USER, assemble(user));
            }
        } catch (Exception e) {
            log.warn("[auth.session.refresh] userId={} {}", userId, e.getMessage());
        }
    }
}
