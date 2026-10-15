package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 调用观测视图：窗口内 SQL 与 HTTP 的汇总指标与慢调用榜 */
@Data
public class ObserveRESP implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 窗口起点，毫秒时间戳 */
    private Long windowStartMs;
    /** 窗口长度，毫秒 */
    private Long windowMs;
    /** SQL 执行汇总 */
    private SqlObserve sql;
    /** HTTP 请求汇总 */
    private HttpObserve http;

    /** SQL 汇总：次数、慢查询次数与耗时 */
    @Data
    public static class SqlObserve implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long count;
        /** 耗时达到慢查询阈值的次数 */
        private Long slowCount;
        /** 平均耗时，毫秒 */
        private Long avgMs;
        /** 最慢一次耗时，毫秒 */
        private Long maxMs;
        /** 慢语句榜，按最慢耗时倒序 */
        private List<CallStatVO> top;
    }

    /** HTTP 汇总：次数、失败次数与耗时 */
    @Data
    public static class HttpObserve implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        private Long count;
        /** 状态码 >= 500 的次数 */
        private Long errorCount;
        /** 平均耗时，毫秒 */
        private Long avgMs;
        /** 最慢一次耗时，毫秒 */
        private Long maxMs;
        /** 慢接口榜，按最慢耗时倒序 */
        private List<CallStatVO> top;
    }

    /** 单个被观测目标的累计：SQL 以语句模板为键，HTTP 以规范化路由为键 */
    @Data
    public static class CallStatVO implements Serializable {

        @Serial
        private static final long serialVersionUID = 1L;

        /** 观测键：SQL 语句模板或 HTTP 路由模式 */
        private String key;
        private Long count;
        /** 平均耗时，毫秒 */
        private Long avgMs;
        /** 最慢一次耗时，毫秒 */
        private Long maxMs;
        /** 最近一次发生时间，毫秒时间戳 */
        private Long lastTs;
        /** 最近一次的附加样本：SQL 为 mapper 方法，HTTP 为查询串 */
        private String sample;
    }
}
