package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbEventDO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/** 运行事件表数据访问 */
@Mapper
public interface WbEventMapper {

    /** 批量落事件 */
    int insertBatch(@Param("list") List<WbEventDO> events);

    /** 按 seq 增量回放 */
    @Select("""
            SELECT * FROM wb_event
            WHERE run_id = #{runId} AND seq > #{afterSeq}
            ORDER BY seq ASC
            LIMIT #{size}
            """)
    List<WbEventDO> selectByRunAfterSeq(@Param("runId") String runId,
                                        @Param("afterSeq") long afterSeq,
                                        @Param("size") long size);

    /** 运行内最大 seq，异常恢复用 */
    @Select("SELECT coalesce(max(seq), 0) FROM wb_event WHERE run_id = #{runId}")
    long maxSeqByRun(@Param("runId") String runId);

    /** 工具调用分布：近 N 天 tool.call 按工具名聚合 */
    @Select("""
            SELECT payload->>'toolName' AS name, count(*) AS value
            FROM wb_event
            WHERE event_type = 'tool.call'
              AND payload->>'phase' = 'start'
              AND occurred_at >= now() - (#{days} || ' days')::interval
            GROUP BY payload->>'toolName'
            ORDER BY value DESC
            """)
    List<Map<String, Object>> toolDistribution(@Param("days") int days);
}
