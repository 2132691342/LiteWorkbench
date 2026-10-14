package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.UserPageREQ;
import com.liteworkbench.domain.entity.SysUserDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 用户表与用户-角色关系数据访问；分页等动态 SQL 在 SysUserMapper.xml */
@Mapper
public interface SysUserMapper {

    /** 新增用户 */
    @Insert("""
            INSERT INTO sys_user (user_id, username, password_hash, password_salt, nickname,
                                  email, avatar_url, state, pwd_reset_required, created_by, remark)
            VALUES (#{userId}, #{username}, #{passwordHash}, #{passwordSalt}, #{nickname},
                    #{email}, #{avatarUrl}, #{state}, #{pwdResetRequired}, #{createdBy}, #{remark})
            """)
    int insert(SysUserDO user);

    /** 按主键更新资料与状态 */
    int update(SysUserDO user);

    /** 更新密码摘要与盐，并显式落是否需要强制改密的标记 */
    @Update("""
            UPDATE sys_user
            SET password_hash = #{passwordHash}, password_salt = #{passwordSalt},
                pwd_reset_required = #{pwdResetRequired},
                updated_by = #{updatedBy}, updated_at = now()
            WHERE user_id = #{userId} AND deleted = false
            """)
    int updatePassword(@Param("userId") String userId,
                       @Param("passwordHash") String passwordHash,
                       @Param("passwordSalt") String passwordSalt,
                       @Param("pwdResetRequired") boolean pwdResetRequired,
                       @Param("updatedBy") String updatedBy);

    /** 记录最近登录时间 */
    @Update("""
            UPDATE sys_user SET last_login_at = now()
            WHERE user_id = #{userId} AND deleted = false
            """)
    int updateLastLogin(@Param("userId") String userId);

    /** 更新状态 */
    @Update("""
            UPDATE sys_user
            SET state = #{state}, updated_by = #{updatedBy}, updated_at = now()
            WHERE user_id = #{userId} AND deleted = false
            """)
    int updateState(@Param("userId") String userId,
                    @Param("state") String state,
                    @Param("updatedBy") String updatedBy);

    /** 逻辑删除 */
    @Update("""
            UPDATE sys_user
            SET deleted = true, state = 'disabled', updated_by = #{updatedBy}, updated_at = now()
            WHERE user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId, @Param("updatedBy") String updatedBy);

    /** 按主键查询 */
    @Select("""
            SELECT user_id, username, password_hash, password_salt, nickname, email, avatar_url,
                   last_login_at, pwd_reset_required, state, created_by, created_at, updated_by,
                   updated_at, deleted, remark
            FROM sys_user
            WHERE user_id = #{userId} AND deleted = false
            """)
    SysUserDO selectById(@Param("userId") String userId);

    /** 按登录名查询 */
    @Select("""
            SELECT user_id, username, password_hash, password_salt, nickname, email, avatar_url,
                   last_login_at, pwd_reset_required, state, created_by, created_at, updated_by,
                   updated_at, deleted, remark
            FROM sys_user
            WHERE username = #{username} AND deleted = false
            """)
    SysUserDO selectByUsername(@Param("username") String username);

    /** 全部未删除用户 ID，内置技能补种用 */
    @Select("SELECT user_id FROM sys_user WHERE deleted = false")
    List<String> selectAllUserIds();

    /** 分页查询 */
    List<SysUserDO> selectPage(@Param("req") UserPageREQ req,
                               @Param("offset") long offset,
                               @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") UserPageREQ req);

    /** 登录名是否被占用；excludeId 为 null 时 PG 无法推断参数类型，必须显式 CAST（同 jsonb 列约定） */
    @Select("""
            SELECT COUNT(1)
            FROM sys_user
            WHERE username = #{username} AND deleted = false
              AND (CAST(#{excludeId} AS varchar) IS NULL OR #{excludeId} = '' OR user_id <> #{excludeId})
            """)
    long countByUsername(@Param("username") String username,
                         @Param("excludeId") String excludeId);

    /** 用户角色 ID 列表 */
    @Select("SELECT role_id FROM sys_user_role WHERE user_id = #{userId}")
    List<String> selectRoleIdsByUserId(@Param("userId") String userId);

    /** 批量写入用户-角色关系 */
    int insertUserRoles(@Param("userId") String userId,
                        @Param("roleIds") List<String> roleIds,
                        @Param("createdBy") String createdBy);

    /** 清空用户-角色关系 */
    @Delete("DELETE FROM sys_user_role WHERE user_id = #{userId}")
    int deleteUserRoles(@Param("userId") String userId);
}
