package com.liteworkbench.mapper;

import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.entity.WbRunDO;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/** 运行表数据访问 */
@Mapper
public interface WbRunMapper {

    /** 新增运行 */
    @Insert("""
            INSERT INTO wb_run (run_id, user_id, session_id, parent_run_id, relation,
                                prompt, model_name, status, created_at)
            VALUES (#{runId}, #{userId}, #{sessionId}, #{parentRunId}, #{relation},
                    #{prompt}, #{modelName}, #{status}, now())
            """)
    int insert(WbRunDO run);

    /** 标记开始 */
    @Update("""
            UPDATE wb_run SET status = 'running', started_at = now()
            WHERE run_id = #{runId} AND status = 'queued'
            """)
    int markRunning(@Param("runId") String runId);

    /** 写入终态与用量 */
    @Update("""
            UPDATE wb_run
            SET status = #{status}, stop_reason = #{stopReason}, iter_count = #{iterCount},
                input_tokens = #{inputTokens}, output_tokens = #{outputTokens},
                ended_at = now(), duration_ms = #{durationMs}
            WHERE run_id = #{runId}
            """)
    int updateTerminal(WbRunDO run);

    /** 按主键查询 */
    @Select("SELECT * FROM wb_run WHERE run_id = #{runId}")
    WbRunDO selectById(@Param("runId") String runId);

    /** 运行分页，按会话过滤 */
    List<WbRunDO> selectPage(@Param("req") SessionPageREQ req,
                             @Param("offset") long offset,
                             @Param("size") long size);

    /** 运行分页计数 */
    long countPage(@Param("req") SessionPageREQ req);

    /** 运行总数，仪表盘用 */
    @Select("SELECT count(*) FROM wb_run")
    long countAll();

    /** 今日运行数 */
    @Select("SELECT count(*) FROM wb_run WHERE created_at >= date_trunc('day', now())")
    long countToday();

    /** 累计 token */
    @Select("SELECT coalesce(sum(input_tokens + output_tokens), 0) FROM wb_run")
    long sumTokens();

    /** 近 N 天运行趋势，返回 date / value 两列 */
    List<Map<String, Object>> runTrendByDay(@Param("days") int days);

    /** 近 N 天 token 趋势 */
    List<Map<String, Object>> tokenTrendByDay(@Param("days") int days);
}
