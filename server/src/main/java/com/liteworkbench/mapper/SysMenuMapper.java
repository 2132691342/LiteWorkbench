package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.SysMenuDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 菜单表数据访问；用户可见菜单与权限点多表关联在 SysMenuMapper.xml */
@Mapper
public interface SysMenuMapper {

    /** 新增菜单 */
    @Insert("""
            INSERT INTO sys_menu (menu_id, parent_id, menu_name, menu_type, route_path, component,
                                  icon, perm_code, sort_no, visible, state, created_by, remark)
            VALUES (#{menuId}, #{parentId}, #{menuName}, #{menuType}, #{routePath}, #{component},
                    #{icon}, #{permCode}, #{sortNo}, #{visible}, #{state}, #{createdBy}, #{remark})
            """)
    int insert(SysMenuDO menu);

    /** 按主键更新 */
    int update(SysMenuDO menu);

    /** 按主键查询 */
    @Select("""
            SELECT menu_id, parent_id, menu_name, menu_type, route_path, component, icon, perm_code,
                   sort_no, visible, state, created_by, created_at, updated_by, updated_at, deleted, remark
            FROM sys_menu
            WHERE menu_id = #{menuId} AND deleted = false
            """)
    SysMenuDO selectById(@Param("menuId") String menuId);

    /** 全量菜单，按父级与排序排列 */
    @Select("""
            SELECT menu_id, parent_id, menu_name, menu_type, route_path, component, icon, perm_code,
                   sort_no, visible, state, created_by, created_at, updated_by, updated_at, deleted, remark
            FROM sys_menu
            WHERE deleted = false
            ORDER BY sort_no ASC, created_at ASC
            """)
    List<SysMenuDO> selectAll();

    /** 用户可见的目录与菜单（不含权限点） */
    List<SysMenuDO> selectVisibleByUserId(@Param("userId") String userId);

    /** 用户权限标识集合 */
    List<String> selectPermCodesByUserId(@Param("userId") String userId);

    /** 权限标识是否被占用 */
    long countByPermCode(@Param("permCode") String permCode, @Param("excludeId") String excludeId);

    /** 直接子节点数量 */
    @Select("SELECT COUNT(1) FROM sys_menu WHERE parent_id = #{parentId} AND deleted = false")
    long countChildren(@Param("parentId") String parentId);

    /** 逻辑删除 */
    @Update("""
            UPDATE sys_menu
            SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE menu_id = #{menuId} AND deleted = false
            """)
    int deleteById(@Param("menuId") String menuId, @Param("updatedBy") String updatedBy);
}
