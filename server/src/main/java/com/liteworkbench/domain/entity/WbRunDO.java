package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 运行实体，映射 wb_run */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbRunDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 运行 ID，RUN_ 前缀 */
    private String runId;
    /** 归属用户 */
    private String userId;
    /** 会话 ID */
    private String sessionId;
    /** 父运行 ID，预留 */
    private String parentRunId;
    /** 派生关系，MVP 恒为 original */
    private String relation;
    /** 本次输入 */
    private String prompt;
    /** 实际使用模型 */
    private String modelName;
    /** 状态 queued / running / success / failed / aborted */
    private String status;
    /** 停止原因 completed / max_iters / aborted / error */
    private String stopReason;
    /** 迭代轮次 */
    private Integer iterCount;
    /** 输入 token */
    private Integer inputTokens;
    /** 输出 token */
    private Integer outputTokens;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    /** 耗时毫秒 */
    private Long durationMs;
    private LocalDateTime createdAt;
}
