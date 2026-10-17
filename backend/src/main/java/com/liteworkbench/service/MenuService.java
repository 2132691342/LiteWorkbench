package com.liteworkbench.service;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.config.StpInterfaceImpl;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MenuTypeEnum;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.constant.StateEnum;
import com.liteworkbench.domain.entity.SysMenuDO;
import com.liteworkbench.domain.dto.MenuREQ;
import com.liteworkbench.domain.vo.MenuVO;
import com.liteworkbench.mapper.SysMenuMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 菜单与权限点：树构建、用户可见性、CRUD */
@Slf4j
@Service
@RequiredArgsConstructor
public class MenuService {

    /** 根节点父 ID */
    private static final String ROOT_PARENT_ID = "0";
    /** 用户菜单树缓存时长，秒 */
    private static final long MENU_CACHE_SECONDS = 600L;

    private final SysMenuMapper sysMenuMapper;
    private final StpInterfaceImpl stpInterface;
    private final StringRedisTemplate stringRedisTemplate;

    /** 全量菜单树，管理页使用 */
    public List<MenuVO> tree() {
        return buildTree(sysMenuMapper.selectAll());
    }

    /** 用户可见菜单树，缓存 10 分钟，动态路由加载不回源；变更点显式清缓存 */
    public List<MenuVO> userTree(String userId) {
        String cacheKey = RedisKeyConst.cacheMenu(userId);
        try {
            String cached = stringRedisTemplate.opsForValue().get(cacheKey);
            if (StrUtil.isNotBlank(cached)) {
                return JSONUtil.toList(cached, MenuVO.class);
            }
        } catch (Exception e) {
            log.warn("[menu.cache] 读取失败 {}", e.getMessage());
        }
        List<MenuVO> tree = buildTree(sysMenuMapper.selectVisibleByUserId(userId));
        try {
            stringRedisTemplate.opsForValue().set(cacheKey, JSONUtil.toJsonStr(tree),
                    Duration.ofSeconds(MENU_CACHE_SECONDS));
        } catch (Exception e) {
            log.warn("[menu.cache] 写入失败 {}", e.getMessage());
        }
        return tree;
    }

    /** 清空全部用户菜单缓存，菜单定义或角色授权变更后调用 */
    public void clearAllMenuCache() {
        try {
            Set<String> keys = stringRedisTemplate.keys(StrUtil.format(RedisKeyConst.CACHE_MENU, "*"));
            if (CollUtil.isNotEmpty(keys)) {
                stringRedisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("[menu.cache.clear] 清空失败 {}", e.getMessage());
        }
    }

    /** 清除指定用户菜单缓存，用户角色变更后调用 */
    public void clearMenuCache(String userId) {
        try {
            stringRedisTemplate.delete(RedisKeyConst.cacheMenu(userId));
        } catch (Exception e) {
            log.warn("[menu.cache.clear] 清除失败 userId={} {}", userId, e.getMessage());
        }
    }

    /** 用户权限标识集合 */
    public List<String> permsOf(String userId) {
        return sysMenuMapper.selectPermCodesByUserId(userId);
    }

    /** 按 ID 查菜单详情 */
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
        stpInterface.clearAllPermCache();
        clearAllMenuCache();
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
        // 祖先链闭环校验：两步更新可造成 A→B→A 环，构建树时会丢节点且不报错
        if (StrUtil.isNotBlank(req.getParentId()) && !ROOT_PARENT_ID.equals(req.getParentId())
                && isDescendantOf(req.getMenuId(), req.getParentId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "父菜单不能是自己的子级");
        }
        SysMenuDO menu = new SysMenuDO();
        BeanUtil.copyProperties(req, menu);
        menu.setMenuId(exists.getMenuId());
        menu.setUpdatedBy(operatorId);
        sysMenuMapper.update(menu);
        log.info("[menu.update] menuId={} name={}", menu.getMenuId(), menu.getMenuName());
        stpInterface.clearAllPermCache();
        clearAllMenuCache();
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
        stpInterface.clearAllPermCache();
        clearAllMenuCache();
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

    /** candidate 是否为 menuId 的子孙：沿祖先链上行，遇环或不存在即否 */
    private boolean isDescendantOf(String menuId, String candidateId) {
        String current = candidateId;
        for (int i = 0; i < 64 && StrUtil.isNotBlank(current) && !ROOT_PARENT_ID.equals(current); i++) {
            if (Objects.equals(current, menuId)) {
                return true;
            }
            SysMenuDO parent = sysMenuMapper.selectById(current);
            if (Objects.isNull(parent)) {
                return false;
            }
            current = parent.getParentId();
        }
        return false;
    }

    private MenuVO toVO(SysMenuDO menu) {
        MenuVO vo = new MenuVO();
        BeanUtil.copyProperties(menu, vo);
        return vo;
    }
}
