package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.SysUserDO;
import com.liteworkbench.domain.dto.UserPageREQ;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 用户表与用户-角色关系数据访问 */
@Mapper
public interface SysUserMapper {

    /** 新增用户 */
    int insert(SysUserDO user);

    /** 按主键更新资料与状态 */
    int update(SysUserDO user);

    /** 更新密码摘要与盐 */
    int updatePassword(@Param("userId") String userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("passwordSalt") String passwordSalt,
                       @Param("updatedBy") String updatedBy);

    /** 记录最近登录时间 */
    int updateLastLogin(@Param("userId") String userId);

    /** 更新状态 */
    int updateState(@Param("userId") String userId,
                    @Param("state") String state,
                    @Param("updatedBy") String updatedBy);

    /** 逻辑删除 */
    int deleteById(@Param("userId") String userId, @Param("updatedBy") String updatedBy);

    /** 按主键查询 */
    SysUserDO selectById(@Param("userId") String userId);

    /** 按登录名查询 */
    SysUserDO selectByUsername(@Param("username") String username);

    /** 分页查询 */
    List<SysUserDO> selectPage(@Param("req") UserPageREQ req,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") UserPageREQ req);

    /** 登录名是否被占用 */
    long countByUsername(@Param("username") String username,
                         @Param("excludeId") String excludeId);

    /** 用户角色 ID 列表 */
    List<String> selectRoleIdsByUserId(@Param("userId") String userId);

    /** 批量写入用户-角色关系 */
    int insertUserRoles(@Param("userId") String userId,
                        @Param("roleIds") List<String> roleIds,
                        @Param("createdBy") String createdBy);

    /** 清空用户-角色关系 */
    int deleteUserRoles(@Param("userId") String userId);
}
