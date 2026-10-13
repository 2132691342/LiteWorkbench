package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.SysRoleDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 角色表与角色-菜单关系数据访问；多表关联与动态 SQL 在 SysRoleMapper.xml */
@Mapper
public interface SysRoleMapper {

    /** 新增角色 */
    @Insert("""
            INSERT INTO sys_role (role_id, role_code, role_name, sort_no, state, created_by, remark)
            VALUES (#{roleId}, #{roleCode}, #{roleName}, #{sortNo}, #{state}, #{createdBy}, #{remark})
            """)
    int insert(SysRoleDO role);

    /** 按主键更新名称、排序等可变字段 */
    int update(SysRoleDO role);

    /** 按主键查询 */
    @Select("""
            SELECT role_id, role_code, role_name, sort_no, state,
                   created_by, created_at, updated_by, updated_at, deleted, remark
            FROM sys_role
            WHERE role_id = #{roleId} AND deleted = false
            """)
    SysRoleDO selectById(@Param("roleId") String roleId);

    /** 按角色码查询，种子对账与幂等补通用 */
    @Select("""
            SELECT role_id, role_code, role_name, sort_no, state,
                   created_by, created_at, updated_by, updated_at, deleted, remark
            FROM sys_role
            WHERE role_code = #{roleCode} AND deleted = false
            """)
    SysRoleDO selectByCode(@Param("roleCode") String roleCode);

    /** 全量列表，按排序与创建时间 */
    @Select("""
            SELECT role_id, role_code, role_name, sort_no, state,
                   created_by, created_at, updated_by, updated_at, deleted, remark
            FROM sys_role
            WHERE deleted = false AND state = 'normal'
            ORDER BY sort_no ASC, created_at ASC
            """)
    List<SysRoleDO> selectList();

    /** 分页查询 */
    List<SysRoleDO> selectPage(@Param("keyword") String keyword,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("keyword") String keyword);

    /** 角色码是否被占用 */
    @Select("""
            SELECT COUNT(1)
            FROM sys_role
            WHERE role_code = #{roleCode} AND deleted = false
              AND (#{excludeId} IS NULL OR #{excludeId} = '' OR role_id <> #{excludeId})
            """)
    long countByCode(@Param("roleCode") String roleCode,
                     @Param("excludeId") String excludeId);

    /** 用户已分配角色列表 */
    List<SysRoleDO> selectByUserId(@Param("userId") String userId);

    /** 被用户引用次数 */
    long countUserRefs(@Param("roleId") String roleId);

    /** 逻辑删除 */
    @Update("""
            UPDATE sys_role
            SET deleted = true, state = 'disabled', updated_by = #{updatedBy}, updated_at = now()
            WHERE role_id = #{roleId} AND deleted = false
            """)
    int deleteById(@Param("roleId") String roleId, @Param("updatedBy") String updatedBy);

    /** 角色已授权菜单 ID 列表 */
    @Select("SELECT menu_id FROM sys_role_menu WHERE role_id = #{roleId}")
    List<String> selectMenuIdsByRoleId(@Param("roleId") String roleId);

    /** 批量写入角色-菜单关系 */
    int insertRoleMenus(@Param("roleId") String roleId,
                        @Param("menuIds") List<String> menuIds,
                        @Param("createdBy") String createdBy);

    /** 清空角色-菜单关系 */
    @Delete("DELETE FROM sys_role_menu WHERE role_id = #{roleId}")
    int deleteRoleMenus(@Param("roleId") String roleId);

    /** 按菜单清理所有角色的授权关系，菜单下线时用 */
    @Delete("DELETE FROM sys_role_menu WHERE menu_id = #{menuId}")
    int deleteRoleMenusByMenuId(@Param("menuId") String menuId);

    /** 角色下的用户 ID 列表，用于清理权限缓存 */
    List<String> selectUserIdsByRoleId(@Param("roleId") String roleId);
}
