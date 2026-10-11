package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.SysRoleDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 角色表与角色-菜单关系数据访问 */
@Mapper
public interface SysRoleMapper {

    /** 新增角色 */
    int insert(SysRoleDO role);

    /** 按主键更新名称、排序等可变字段 */
    int update(SysRoleDO role);

    /** 按主键查询 */
    SysRoleDO selectById(@Param("roleId") String roleId);

    /** 全量列表 */
    List<SysRoleDO> selectList();

    /** 分页查询 */
    List<SysRoleDO> selectPage(@Param("keyword") String keyword,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("keyword") String keyword);

    /** 角色码是否被占用 */
    long countByCode(@Param("roleCode") String roleCode,
                     @Param("excludeId") String excludeId);

    /** 用户已分配角色列表 */
    List<SysRoleDO> selectByUserId(@Param("userId") String userId);

    /** 被用户引用次数 */
    long countUserRefs(@Param("roleId") String roleId);

    /** 逻辑删除 */
    int deleteById(@Param("roleId") String roleId, @Param("updatedBy") String updatedBy);

    /** 角色已授权菜单 ID 列表 */
    List<String> selectMenuIdsByRoleId(@Param("roleId") String roleId);

    /** 批量写入角色-菜单关系 */
    int insertRoleMenus(@Param("roleId") String roleId,
                        @Param("menuIds") List<String> menuIds,
                        @Param("createdBy") String createdBy);

    /** 清空角色-菜单关系 */
    int deleteRoleMenus(@Param("roleId") String roleId);

    /** 角色下的用户 ID 列表，用于清理权限缓存 */
    List<String> selectUserIdsByRoleId(@Param("roleId") String roleId);
}
