package com.liteworkbench.domain.entity;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 运行事件实体，映射 wb_event */
@Data
public class WbEventDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 事件 ID，EVNT_ 前缀 */
    private String eventId;
    /** 运行 ID */
    private String runId;
    /** 会话 ID，冗余便于检索 */
    private String sessionId;
    /** 运行内序号，从 1 递增 */
    private Integer seq;
    /** 归一化事件类型 */
    private String eventType;
    /** 内容块类型 text / thinking / tool_call / tool_result */
    private String blockType;
    /** 增量文本 */
    private String textDelta;
    /** 完整事件体，jsonb */
    private String payload;
    private LocalDateTime occurredAt;
}
