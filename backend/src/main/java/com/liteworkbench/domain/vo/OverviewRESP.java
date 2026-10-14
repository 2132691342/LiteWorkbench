package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

/** 仪表盘汇总视图 */
@Data
public class OverviewRESP implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private Long sessionCount;
    private Long runCount;
    /** 成功运行数，成功率用 */
    private Long successRunCount;
    private Long todayRunCount;
    private Long totalTokens;
    /** 近 7 天运行趋势，date -> count */
    private List<TrendPoint> runTrend;
    /** 工具调用分布，toolName -> count */
    private Map<String, Long> toolDistribution;
    /** 近 7 天 token 趋势，date -> tokens */
    private List<TrendPoint> tokenTrend;

    /** 趋势点 */
    @Data
    public static class TrendPoint implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 日期，yyyyMMdd */
        private String date;
        private Long value;

        public TrendPoint() {
        }

        public TrendPoint(String date, Long value) {
            this.date = date;
            this.value = value;
        }
    }
}
