package com.liteworkbench.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.config.StpInterfaceImpl;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.RoleCodeConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.entity.SysRoleDO;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.RoleGrantREQ;
import com.liteworkbench.domain.dto.RoleREQ;
import com.liteworkbench.domain.vo.RoleVO;
import com.liteworkbench.mapper.SysRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

/** 角色：CRUD、菜单授权与权限缓存失效 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RoleService {

    private final SysRoleMapper sysRoleMapper;
    private final StpInterfaceImpl stpInterface;

    /** 分页查询 */
    public PageResult<RoleVO> page(PageREQ req) {
        long total = sysRoleMapper.countPage(req.getKeyword());
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<RoleVO> list = sysRoleMapper
                .selectPage(req.getKeyword(), req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 全量角色列表 */
    public List<RoleVO> list() {
        return sysRoleMapper.selectList().stream().map(this::toVO).toList();
    }

    /** 角色详情 */
    public RoleVO detail(String roleId) {
        return toVO(require(roleId));
    }

    /** 已授权菜单 ID */
    public List<String> menuIds(String roleId) {
        require(roleId);
        List<String> menuIds = sysRoleMapper.selectMenuIdsByRoleId(roleId);
        return Objects.isNull(menuIds) ? CollUtil.newArrayList() : menuIds;
    }

    /** 新建角色 */
    @Transactional(rollbackFor = Exception.class)
    public String create(RoleREQ req, String operatorId) {
        if (sysRoleMapper.countByCode(req.getRoleCode(), null) > 0) {
            throw new BizException(ErrorCodeEnum.ROLE_CODE_CONFLICT, req.getRoleCode());
        }
        SysRoleDO role = new SysRoleDO();
        BeanUtil.copyProperties(req, role);
        role.setRoleId(IdGenerator.generate(IdPrefixConst.ROLE));
        role.setSortNo(Objects.isNull(req.getSortNo()) ? 0 : req.getSortNo());
        role.setState(StateEnum.NORMAL.getCode());
        role.setCreatedBy(operatorId);
        sysRoleMapper.insert(role);
        log.info("[role.create] roleId={} code={}", role.getRoleId(), role.getRoleCode());
        return role.getRoleId();
    }

    /** 修改角色，内置角色只允许改名称与备注 */
    @Transactional(rollbackFor = Exception.class)
    public void update(RoleREQ req, String operatorId) {
        SysRoleDO exists = require(req.getRoleId());
        SysRoleDO role = new SysRoleDO();
        role.setRoleId(exists.getRoleId());
        role.setRoleName(req.getRoleName());
        role.setSortNo(req.getSortNo());
        role.setRemark(req.getRemark());
        role.setUpdatedBy(operatorId);
        sysRoleMapper.update(role);
        log.info("[role.update] roleId={}", role.getRoleId());
    }

    /** 删除角色，内置角色与被引用的角色不允许删除 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String roleId, String operatorId) {
        SysRoleDO role = require(roleId);
        if (RoleCodeConst.isBuiltin(role.getRoleCode())) {
            throw new BizException(ErrorCodeEnum.BUILTIN_ROLE_IMMUTABLE, role.getRoleCode());
        }
        if (sysRoleMapper.countUserRefs(roleId) > 0) {
            throw new BizException(ErrorCodeEnum.ROLE_IN_USE, role.getRoleName());
        }
        sysRoleMapper.deleteById(roleId, operatorId);
        sysRoleMapper.deleteRoleMenus(roleId);
        log.info("[role.delete] roleId={}", roleId);
    }

    /** 全量授权菜单，先清后写并失效相关用户权限缓存 */
    @Transactional(rollbackFor = Exception.class)
    public void grant(RoleGrantREQ req, String operatorId) {
        require(req.getRoleId());
        sysRoleMapper.deleteRoleMenus(req.getRoleId());
        if (CollUtil.isNotEmpty(req.getMenuIds())) {
            sysRoleMapper.insertRoleMenus(req.getRoleId(), req.getMenuIds(), operatorId);
        }
        evictPermCache(req.getRoleId());
        log.info("[role.grant] roleId={} menuCount={}", req.getRoleId(), CollUtil.size(req.getMenuIds()));
    }

    /** 清除角色下所有用户的权限缓存 */
    public void evictPermCache(String roleId) {
        List<String> userIds = sysRoleMapper.selectUserIdsByRoleId(roleId);
        if (CollUtil.isNotEmpty(userIds)) {
            userIds.forEach(stpInterface::clearPermCache);
        }
    }

    private SysRoleDO require(String roleId) {
        SysRoleDO role = sysRoleMapper.selectById(roleId);
        if (Objects.isNull(role)) {
            throw new BizException(ErrorCodeEnum.ROLE_NOT_FOUND, roleId);
        }
        return role;
    }

    private RoleVO toVO(SysRoleDO role) {
        RoleVO vo = new RoleVO();
        BeanUtil.copyProperties(role, vo);
        vo.setBuiltin(RoleCodeConst.isBuiltin(role.getRoleCode()));
        vo.setCreatedAt(Objects.isNull(role.getCreatedAt())
                ? null : LocalDateTimeUtil.toEpochMilli(role.getCreatedAt()));
        return vo;
    }
}
