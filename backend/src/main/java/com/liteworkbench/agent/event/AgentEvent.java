package com.liteworkbench.agent.event;

import lombok.Builder;
import lombok.Data;

/** 内核事件：一次运行内的最小事实单元，尚未分配 seq、尚未区分 run 归属 */
@Data
@Builder
public class AgentEvent {

    private AgentEventType type;
    /** 增量正文或思考 */
    private String textDelta;
    private String toolName;
    private String toolCallId;
    /** 工具入参 JSON */
    private String arguments;
    /** 工具输出或错误文本 */
    private String output;
    /** 工具是否失败 */
    private Boolean error;
    private Long inputTokens;
    private Long outputTokens;
    /** 模型调用耗时，毫秒 */
    private Long durationMs;
    /** 迭代上限 */
    private Integer maxIters;
    /** 完整正文（AGENT_RESULT）或错误信息（AGENT_ERROR） */
    private String message;
    /** 计划快照 JSON（PLAN_UPDATED），由规划工具归一化产出 */
    private String planJson;
}
