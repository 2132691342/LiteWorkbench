package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 运行视图对象 */
@Data
public class RunVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String runId;
    private String sessionId;
    /** 输入摘要 */
    private String prompt;
    private String modelName;
    private String status;
    private String stopReason;
    private Integer iterCount;
    private Integer inputTokens;
    private Integer outputTokens;
    /** 耗时毫秒 */
    private Long durationMs;
    /** 开始时间，毫秒时间戳 */
    private Long startedAt;
    /** 结束时间，毫秒时间戳 */
    private Long endedAt;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
