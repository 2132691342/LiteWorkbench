package com.liteworkbench.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MenuTypeEnum;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.entity.SysMenuDO;
import com.liteworkbench.domain.dto.MenuREQ;
import com.liteworkbench.domain.vo.MenuVO;
import com.liteworkbench.mapper.SysMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 菜单与权限点：树构建、用户可见性、CRUD */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    /** 根节点父 ID */
    private static final String ROOT_PARENT_ID = "0";

    private final SysMenuMapper sysMenuMapper;

    /** 全量菜单树，管理页使用 */
    public List<MenuVO> tree() {
        return buildTree(sysMenuMapper.selectAll());
    }

    /** 用户可见菜单树，前端动态路由使用 */
    public List<MenuVO> userTree(String userId) {
        return buildTree(sysMenuMapper.selectVisibleByUserId(userId));
    }

    /** 用户权限标识集合 */
    public List<String> permsOf(String userId) {
        return sysMenuMapper.selectPermCodesByUserId(userId);
    }

    /** 菜单详情 */
    public MenuVO detail(String menuId) {
        return toVO(require(menuId));
    }

    /** 新建菜单，权限标识重复即拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public String create(MenuREQ req, String operatorId) {
        if (StrUtil.isNotBlank(req.getPermCode())
                && sysMenuMapper.countByPermCode(req.getPermCode(), null) > 0) {
            throw new BizException(ErrorCodeEnum.MENU_PERM_CONFLICT, req.getPermCode());
        }
        SysMenuDO menu = new SysMenuDO();
        BeanUtil.copyProperties(req, menu);
        menu.setMenuId(IdGenerator.generate(IdPrefixConst.MENU));
        menu.setParentId(StrUtil.blankToDefault(req.getParentId(), ROOT_PARENT_ID));
        menu.setMenuType(StrUtil.blankToDefault(req.getMenuType(), MenuTypeEnum.MENU.getCode()));
        menu.setSortNo(Objects.isNull(req.getSortNo()) ? 0 : req.getSortNo());
        menu.setVisible(Objects.isNull(req.getVisible()) || req.getVisible());
        menu.setState(StateEnum.NORMAL.getCode());
        menu.setCreatedBy(operatorId);
        sysMenuMapper.insert(menu);
        log.info("[menu.create] menuId={} name={} type={}", menu.getMenuId(), menu.getMenuName(), menu.getMenuType());
        return menu.getMenuId();
    }

    /** 修改菜单，权限标识冲突即拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public void update(MenuREQ req, String operatorId) {
        SysMenuDO exists = require(req.getMenuId());
        if (StrUtil.isNotBlank(req.getPermCode())
                && sysMenuMapper.countByPermCode(req.getPermCode(), req.getMenuId()) > 0) {
            throw new BizException(ErrorCodeEnum.MENU_PERM_CONFLICT, req.getPermCode());
        }
        if (Objects.equals(req.getMenuId(), req.getParentId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "父菜单不能是自己");
        }
        SysMenuDO menu = new SysMenuDO();
        BeanUtil.copyProperties(req, menu);
        menu.setMenuId(exists.getMenuId());
        menu.setUpdatedBy(operatorId);
        sysMenuMapper.update(menu);
        log.info("[menu.update] menuId={} name={}", menu.getMenuId(), menu.getMenuName());
    }

    /** 删除菜单，存在子级即拒绝 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(String menuId, String operatorId) {
        require(menuId);
        if (sysMenuMapper.countChildren(menuId) > 0) {
            throw new BizException(ErrorCodeEnum.MENU_HAS_CHILDREN, menuId);
        }
        sysMenuMapper.deleteById(menuId, operatorId);
        log.info("[menu.delete] menuId={}", menuId);
    }

    /** 按 parentId 一次分组构建树，保持 sortNo 顺序 */
    private List<MenuVO> buildTree(List<SysMenuDO> menus) {
        if (CollUtil.isEmpty(menus)) {
            return CollUtil.newArrayList();
        }
        Map<String, List<MenuVO>> childrenMap = new LinkedHashMap<>();
        for (SysMenuDO menu : menus) {
            childrenMap.computeIfAbsent(menu.getParentId(), key -> new ArrayList<>()).add(toVO(menu));
        }
        List<MenuVO> roots = childrenMap.get(ROOT_PARENT_ID);
        if (CollUtil.isEmpty(roots)) {
            roots = CollUtil.newArrayList();
        }
        for (MenuVO node : childrenMap.values().stream().flatMap(List::stream).toList()) {
            node.setChildren(childrenMap.get(node.getMenuId()));
        }
        return roots;
    }

    private SysMenuDO require(String menuId) {
        SysMenuDO menu = sysMenuMapper.selectById(menuId);
        if (Objects.isNull(menu)) {
            throw new BizException(ErrorCodeEnum.MENU_NOT_FOUND, menuId);
        }
        return menu;
    }

    private MenuVO toVO(SysMenuDO menu) {
        MenuVO vo = new MenuVO();
        BeanUtil.copyProperties(menu, vo);
        return vo;
    }
}
