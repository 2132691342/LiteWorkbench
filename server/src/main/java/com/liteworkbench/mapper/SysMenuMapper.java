package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.SysMenuDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 菜单表数据访问 */
@Mapper
public interface SysMenuMapper {

    /** 新增菜单 */
    int insert(SysMenuDO menu);

    /** 按主键更新 */
    int update(SysMenuDO menu);

    /** 按主键查询 */
    SysMenuDO selectById(@Param("menuId") String menuId);

    /** 全量菜单，按父级与排序排列 */
    List<SysMenuDO> selectAll();

    /** 用户可见的目录与菜单（不含权限点） */
    List<SysMenuDO> selectVisibleByUserId(@Param("userId") String userId);

    /** 用户可见的全部菜单 ID，用于管理页回显 */
    List<String> selectMenuIdsByUserId(@Param("userId") String userId);

    /** 用户权限标识集合 */
    List<String> selectPermCodesByUserId(@Param("userId") String userId);

    /** 权限标识是否被占用 */
    long countByPermCode(@Param("permCode") String permCode, @Param("excludeId") String excludeId);

    /** 直接子节点数量 */
    long countChildren(@Param("parentId") String parentId);

    /** 逻辑删除 */
    int deleteById(@Param("menuId") String menuId, @Param("updatedBy") String updatedBy);
}
