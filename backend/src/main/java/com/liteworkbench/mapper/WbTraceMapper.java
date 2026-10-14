package com.liteworkbench.mapper;

import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.domain.vo.UsageStatsVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/** 可观测性数据访问：Run 树、链路片段与用量归因；全是多表聚合或长 SQL，一律落 XML */
@Mapper
public interface WbTraceMapper {

    /** 会话内全部运行（含子运行），Run 树按 parent_run_id 自组装 */
    List<WbRunDO> selectRunsBySession(@Param("userId") String userId,
                                     @Param("sessionId") String sessionId);

    /** 会话内各运行的事件条数，避免按运行逐条 count 造成 N+1 */
    List<Map<String, Object>> selectEventCounts(@Param("sessionId") String sessionId);

    /** 用量概览：运行数、token 合计与平均耗时 */
    UsageStatsVO.Totals selectUsageTotals(@Param("userId") String userId,
                                          @Param("from") Long from,
                                          @Param("to") Long to);

    /** 按天用量，时间正序 */
    List<UsageStatsVO.DailyPoint> selectUsageDaily(@Param("userId") String userId,
                                                   @Param("from") Long from,
                                                   @Param("to") Long to);

    /** 按模型用量，token 降序 */
    List<UsageStatsVO.ModelSlice> selectUsageByModel(@Param("userId") String userId,
                                                     @Param("from") Long from,
                                                     @Param("to") Long to);

    /** 按状态用量 */
    List<UsageStatsVO.StateSlice> selectUsageByState(@Param("userId") String userId,
                                                     @Param("from") Long from,
                                                     @Param("to") Long to);
}
