package com.liteworkbench.domain.vo;

import lombok.Data;

import java.util.List;

/** 日志分布概览：级别计数与按小时时间直方图，驱动日志中心顶部卡片与柱状图 */
@Data
public class LogStatsVO {

    /** 检索视角：global（全局，管理员）/ self（仅本人） */
    private String scope;

    /** 各级别计数，缺级别不补零 */
    private List<LevelCount> levels;

    /** 按小时分桶的计数，桶起点为整点毫秒时间戳，升序 */
    private List<HourCount> histogram;

    /** 单个级别计数 */
    @Data
    public static class LevelCount {
        /** 级别：INFO / WARN / ERROR */
        private String level;
        /** 计数 */
        private long count;
    }

    /** 单个小时桶计数 */
    @Data
    public static class HourCount {
        /** 桶起点，整点毫秒时间戳 */
        private long hour;
        /** 计数 */
        private long count;
    }
}
