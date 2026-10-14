package com.liteworkbench.domain.vo;

import lombok.Data;

import java.util.List;

/** 日志分布概览：级别计数与时间直方图，驱动日志中心顶部卡片与柱状图 */
@Data
public class LogStatsVO {

    /** 检索视角：global（全局，管理员）/ self（仅本人） */
    private String scope;

    /** 各级别计数，缺级别不补零 */
    private List<LevelCount> levels;

    /** 分桶计数，桶起点毫秒时间戳升序；默认按小时分桶，区间超过 7 天按天分桶 */
    private List<HourCount> histogram;

    /** 直方图桶宽毫秒：3600000（小时）/ 86400000（天），前端据此渲染坐标 */
    private long bucketMs = 3_600_000L;

    /** 单个级别计数 */
    @Data
    public static class LevelCount {
        /** 级别：INFO / WARN / ERROR */
        private String level;
        private long count;
    }

    /** 单个小时桶计数 */
    @Data
    public static class HourCount {
        /** 桶起点，整点毫秒时间戳 */
        private long hour;
        private long count;
    }
}
