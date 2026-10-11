package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 运行视图对象 */
@Data
public class RunVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 运行 ID */
    private String runId;
    /** 会话 ID */
    private String sessionId;
    /** 输入摘要 */
    private String prompt;
    /** 模型名 */
    private String modelName;
    /** 状态 */
    private String status;
    /** 停止原因 */
    private String stopReason;
    /** 迭代轮次 */
    private Integer iterCount;
    /** 输入 token */
    private Integer inputTokens;
    /** 输出 token */
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
