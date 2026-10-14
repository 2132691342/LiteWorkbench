package com.liteworkbench.domain.vo;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 用量归因：总量 + 按天 / 按模型 / 按状态的分解，全部来自 wb_run 的账目字段 */
@Data
public class UsageStatsVO {

    /** 概览：运行数、成功数、失败数、输入 token、输出 token、平均耗时 */
    private Totals totals;

    /** 按天分解，时间正序 */
    private List<DailyPoint> daily = new ArrayList<>();

    /** 按模型分解，token 降序 */
    private List<ModelSlice> byModel = new ArrayList<>();

    private List<StateSlice> byState = new ArrayList<>();

    /** 用量概览 */
    @Data
    public static class Totals {
        private Long runCount;
        private Long inputTokens;
        private Long outputTokens;
        /** 平均耗时毫秒 */
        private Long avgDurationMs;
    }

    /** 单日用量 */
    @Data
    public static class DailyPoint {
        /** 日期 yyyy-MM-dd */
        private String day;
        private Long runCount;
        private Long inputTokens;
        private Long outputTokens;
        /** 成功率百分比，0-100 */
        private Double successRate;
    }

    /** 单模型用量 */
    @Data
    public static class ModelSlice {
        private String modelName;
        private Long runCount;
        private Long inputTokens;
        private Long outputTokens;
    }

    /** 单状态用量 */
    @Data
    public static class StateSlice {
        private String state;
        private Long runCount;
    }
}
