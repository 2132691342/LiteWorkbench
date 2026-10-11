package com.liteworkbench.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.PasswordUtil;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.config.AuthProperties;
import com.liteworkbench.config.StpInterfaceImpl;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.UserStateEnum;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.domain.dto.ResetPasswordREQ;
import com.liteworkbench.domain.dto.UserPageREQ;
import com.liteworkbench.domain.dto.UserREQ;
import com.liteworkbench.domain.vo.UserVO;
import com.liteworkbench.mapper.SysRoleMapper;
import com.liteworkbench.mapper.SysUserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 用户：CRUD、角色分配、状态与口令管理 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final SysUserMapper sysUserMapper;
    private final SysRoleMapper sysRoleMapper;
    private final StpInterfaceImpl stpInterface;
    private final AuthProperties authProperties;
    private final MenuService menuService;

    /** 分页查询 */
    public PageResult<UserVO> page(UserPageREQ req) {
        long total = sysUserMapper.countPage(req);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<UserVO> list = sysUserMapper.selectPage(req, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 用户详情 */
    public UserVO detail(String userId) {
        return toVO(require(userId));
    }

    /** 新建用户 */
    @Transactional(rollbackFor = Exception.class)
    public String create(UserREQ req, String operatorId) {
        if (StrUtil.isBlank(req.getUsername())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "登录名不能为空");
        }
        if (sysUserMapper.countByUsername(req.getUsername(), null) > 0) {
            throw new BizException(ErrorCodeEnum.USERNAME_CONFLICT, req.getUsername());
        }
        validateRoles(req.getRoleIds());
        String salt = PasswordUtil.randomSalt();
        String rawPassword = StrUtil.blankToDefault(req.getPassword(), authProperties.getDefaultPassword());
        SysUserDO user = new SysUserDO();
        user.setUserId(IdGenerator.generate(IdPrefixConst.USER));
        user.setUsername(req.getUsername());
        user.setPasswordSalt(salt);
        user.setPasswordHash(PasswordUtil.hash(rawPassword, salt));
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setState(UserStateEnum.NORMAL.getCode());
        user.setCreatedBy(operatorId);
        user.setRemark(req.getRemark());
        sysUserMapper.insert(user);
        if (CollUtil.isNotEmpty(req.getRoleIds())) {
            sysUserMapper.insertUserRoles(user.getUserId(), req.getRoleIds(), operatorId);
        }
        log.info("[user.create] userId={} username={}", user.getUserId(), user.getUsername());
        return user.getUserId();
    }

    /** 修改资料与角色，不允许修改自己的角色 */
    @Transactional(rollbackFor = Exception.class)
    public void update(UserREQ req, String operatorId) {
        SysUserDO exists = require(req.getUserId());
        boolean self = Objects.equals(exists.getUserId(), operatorId);
        if (self && CollUtil.isNotEmpty(req.getRoleIds())) {
            throw new BizException(ErrorCodeEnum.SELF_OPERATION_FORBIDDEN, "修改自己的角色");
        }
        validateRoles(req.getRoleIds());
        SysUserDO user = new SysUserDO();
        user.setUserId(exists.getUserId());
        user.setNickname(req.getNickname());
        user.setEmail(req.getEmail());
        user.setRemark(req.getRemark());
        user.setUpdatedBy(operatorId);
        sysUserMapper.update(user);
        if (!self && Objects.nonNull(req.getRoleIds())) {
            sysUserMapper.deleteUserRoles(exists.getUserId());
            if (CollUtil.isNotEmpty(req.getRoleIds())) {
                sysUserMapper.insertUserRoles(exists.getUserId(), req.getRoleIds(), operatorId);
            }
            stpInterface.clearPermCache(exists.getUserId());
            menuService.clearMenuCache(exists.getUserId());
        }
        log.info("[user.update] userId={} operatorId={}", exists.getUserId(), operatorId);
    }

    /** 启用 / 停用，不允许操作自己 */
    @Transactional(rollbackFor = Exception.class)
    public void toggle(String userId, String operatorId) {
        SysUserDO user = require(userId);
        if (Objects.equals(user.getUserId(), operatorId)) {
            throw new BizException(ErrorCodeEnum.SELF_OPERATION_FORBIDDEN, "停用自己");
        }
        String next = UserStateEnum.NORMAL.getCode().equals(user.getState())
                ? UserStateEnum.DISABLED.getCode() : UserStateEnum.NORMAL.getCode();
        sysUserMapper.updateState(userId, next, operatorId);
        log.info("[user.toggle] userId={} state={}", userId, next);
    }

    /** 重置密码 */
    @Transactional(rollbackFor = Exception.class)
    public void resetPassword(ResetPasswordREQ req, String operatorId) {
        require(req.getUserId());
        String salt = PasswordUtil.randomSalt();
        sysUserMapper.updatePassword(req.getUserId(), PasswordUtil.hash(req.getNewPassword(), salt), salt, operatorId);
        log.info("[user.reset] userId={} operatorId={}", req.getUserId(), operatorId);
    }

    /** 逻辑删除，不允许删除自己 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String userId, String operatorId) {
        require(userId);
        if (Objects.equals(userId, operatorId)) {
            throw new BizException(ErrorCodeEnum.SELF_OPERATION_FORBIDDEN, "删除自己");
        }
        sysUserMapper.deleteById(userId, operatorId);
        sysUserMapper.deleteUserRoles(userId);
        stpInterface.clearPermCache(userId);
        menuService.clearMenuCache(userId);
        log.info("[user.delete] userId={} operatorId={}", userId, operatorId);
    }

    /** 修改自己的密码 */
    @Transactional(rollbackFor = Exception.class)
    public void changePassword(String userId, String oldPassword, String newPassword) {
        SysUserDO user = sysUserMapper.selectById(userId);
        if (Objects.isNull(user)) {
            throw new BizException(ErrorCodeEnum.USER_NOT_FOUND, userId);
        }
        if (!PasswordUtil.matches(oldPassword, user.getPasswordSalt(), user.getPasswordHash())) {
            throw new BizException(ErrorCodeEnum.OLD_PASSWORD_INVALID);
        }
        String salt = PasswordUtil.randomSalt();
        sysUserMapper.updatePassword(userId, PasswordUtil.hash(newPassword, salt), salt, userId);
        log.info("[user.password] userId={}", userId);
    }

    /** 校验角色存在 */
    private void validateRoles(List<String> roleIds) {
        if (CollUtil.isEmpty(roleIds)) {
            return;
        }
        for (String roleId : roleIds) {
            if (Objects.isNull(sysRoleMapper.selectById(roleId))) {
                throw new BizException(ErrorCodeEnum.ROLE_NOT_FOUND, roleId);
            }
        }
    }

    private SysUserDO require(String userId) {
        SysUserDO user = sysUserMapper.selectById(userId);
        if (Objects.isNull(user)) {
            throw new BizException(ErrorCodeEnum.USER_NOT_FOUND, userId);
        }
        return user;
    }

    private UserVO toVO(SysUserDO user) {
        UserVO vo = new UserVO();
        BeanUtil.copyProperties(user, vo);
        vo.setRoleIds(sysUserMapper.selectRoleIdsByUserId(user.getUserId()));
        vo.setLastLoginAt(TimeUtil.toMillis(user.getLastLoginAt()));
        vo.setCreatedAt(TimeUtil.toMillis(user.getCreatedAt()));
        return vo;
    }
}
