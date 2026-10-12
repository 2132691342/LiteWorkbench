package com.liteworkbench.agent.event;

import lombok.Builder;
import lombok.Data;

/** 内核事件：一次运行内的最小事实单元，尚未分配 seq、尚未区分 run 归属 */
@Data
@Builder
public class AgentEvent {

    /** 事件类型 */
    private AgentEventType type;
    /** 增量正文或思考 */
    private String textDelta;
    /** 工具名 */
    private String toolName;
    /** 工具调用 ID */
    private String toolCallId;
    /** 工具入参 JSON */
    private String arguments;
    /** 工具输出或错误文本 */
    private String output;
    /** 工具是否失败 */
    private Boolean error;
    /** 输入 token */
    private Long inputTokens;
    /** 输出 token */
    private Long outputTokens;
    /** 模型调用耗时，毫秒 */
    private Long durationMs;
    /** 迭代上限 */
    private Integer maxIters;
    /** 完整正文（AGENT_RESULT）或错误信息（AGENT_ERROR） */
    private String message;
}
