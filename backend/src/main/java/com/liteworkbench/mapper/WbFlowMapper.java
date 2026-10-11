package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.entity.WbFlowDO;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/** 工作流画布表数据访问；节点与连线整体存 graph_json */
@Mapper
public interface WbFlowMapper {

    /** 新增画布 */
    @Insert("""
            INSERT INTO wb_flow (flow_id, user_id, flow_name, description, graph_json, node_count, state, created_by)
            VALUES (#{flowId}, #{userId}, #{flowName}, #{description},
                    CAST(#{graphJson} AS jsonb), #{nodeCount}, #{state}, #{createdBy})
            """)
    int insert(WbFlowDO flow);

    /** 保存画布与元数据 */
    @Update("""
            UPDATE wb_flow SET flow_name = #{flowName}, description = #{description},
                               graph_json = CAST(#{graphJson} AS jsonb),
                               node_count = #{nodeCount}, state = #{state},
                               updated_by = #{updatedBy}, updated_at = now()
            WHERE flow_id = #{flowId} AND user_id = #{userId} AND deleted = false
            """)
    int update(WbFlowDO flow);

    /** 记录最近一次运行 */
    @Update("""
            UPDATE wb_flow SET last_run_id = #{runId}, updated_at = now()
            WHERE flow_id = #{flowId} AND user_id = #{userId} AND deleted = false
            """)
    int updateLastRun(@Param("flowId") String flowId,
                      @Param("userId") String userId,
                      @Param("runId") String runId);

    /** 逻辑删除 */
    @Delete("""
            UPDATE wb_flow SET deleted = true, updated_by = #{updatedBy}, updated_at = now()
            WHERE flow_id = #{flowId} AND user_id = #{userId} AND deleted = false
            """)
    int deleteById(@Param("userId") String userId,
                   @Param("flowId") String flowId,
                   @Param("updatedBy") String updatedBy);

    /** 按 (用户, 主键) 查询 */
    @Select("""
            SELECT * FROM wb_flow
            WHERE flow_id = #{flowId} AND user_id = #{userId} AND deleted = false
            """)
    WbFlowDO selectByIdAndUser(@Param("userId") String userId, @Param("flowId") String flowId);

    /** 用户内重名校验 */
    @Select("""
            SELECT COUNT(1) FROM wb_flow
            WHERE user_id = #{userId} AND flow_name = #{flowName} AND deleted = false
              AND (#{excludeId} IS NULL OR #{excludeId} = '' OR flow_id != #{excludeId})
            """)
    long countByName(@Param("userId") String userId,
                     @Param("flowName") String flowName,
                     @Param("excludeId") String excludeId);

    /** 分页查询 */
    List<WbFlowDO> selectPage(@Param("req") PageREQ req,
                              @Param("userId") String userId,
                              @Param("offset") long offset,
                              @Param("size") long size);

    /** 分页计数 */
    long countPage(@Param("req") PageREQ req, @Param("userId") String userId);
}
