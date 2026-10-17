package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbWorkspaceDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 工作区表数据访问，查询一律带 user_id 行级隔离 */
@Mapper
public interface WbWorkspaceMapper {

    @Insert("""
            INSERT INTO wb_workspace (workspace_id, user_id, workspace_name, bucket_name,
                                      description, is_default, created_by)
            VALUES (#{workspaceId}, #{userId}, #{workspaceName}, #{bucketName},
                    #{description}, #{isDefault}, #{createdBy})
            """)
    int insert(WbWorkspaceDO workspace);

    @Update("""
            UPDATE wb_workspace
            SET workspace_name = #{workspaceName}, description = #{description},
                updated_by = #{updatedBy}, updated_at = now()
            WHERE workspace_id = #{workspaceId} AND deleted = false
            """)
    int update(WbWorkspaceDO workspace);

    /** 默认工作区切换：先把存量默认行摘除再置位，两步在同一事务内由调用方保证 */
    @Update("""
            UPDATE wb_workspace SET is_default = false, updated_by = #{updatedBy}, updated_at = now()
            WHERE user_id = #{userId} AND is_default = true AND deleted = false
            """)
    int clearDefault(@Param("userId") String userId, @Param("updatedBy") String updatedBy);

    @Update("""
            UPDATE wb_workspace SET is_default = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE workspace_id = #{workspaceId} AND deleted = false
            """)
    int markDefault(@Param("workspaceId") String workspaceId, @Param("updatedBy") String updatedBy);

    @Delete("""
            UPDATE wb_workspace SET deleted = true, is_default = false,
                updated_by = #{updatedBy}, updated_at = now()
            WHERE workspace_id = #{workspaceId} AND deleted = false
            """)
    int deleteById(@Param("workspaceId") String workspaceId, @Param("updatedBy") String updatedBy);

    @Select("""
            SELECT * FROM wb_workspace
            WHERE workspace_id = #{workspaceId} AND user_id = #{userId} AND deleted = false
            """)
    WbWorkspaceDO selectByIdAndUser(@Param("workspaceId") String workspaceId, @Param("userId") String userId);

    @Select("""
            SELECT * FROM wb_workspace
            WHERE user_id = #{userId} AND deleted = false ORDER BY is_default DESC, created_at ASC
            """)
    List<WbWorkspaceDO> selectByUser(@Param("userId") String userId);

    @Select("""
            SELECT * FROM wb_workspace
            WHERE user_id = #{userId} AND is_default = true AND deleted = false
            """)
    WbWorkspaceDO selectDefault(@Param("userId") String userId);

    @Select("""
            SELECT count(*) FROM wb_workspace
            WHERE user_id = #{userId} AND deleted = false
            """)
    long countByUser(@Param("userId") String userId);

    @Select("""
            SELECT count(*) FROM wb_workspace
            WHERE user_id = #{userId} AND workspace_name = #{workspaceName} AND deleted = false
              AND (CAST(#{excludeId} AS varchar) IS NULL OR #{excludeId} = '' OR workspace_id <> #{excludeId})
            """)
    long countByNameExclude(@Param("userId") String userId, @Param("workspaceName") String workspaceName,
                            @Param("excludeId") String excludeId);

    /** 桶名唯一性校验：全局唯一，与用户无关 */
    @Select("""
            SELECT count(*) FROM wb_workspace WHERE bucket_name = #{bucketName} AND deleted = false
            """)
    long countByBucket(@Param("bucketName") String bucketName);
}
