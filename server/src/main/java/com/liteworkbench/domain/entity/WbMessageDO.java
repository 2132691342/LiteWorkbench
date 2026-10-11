package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 会话消息实体，映射 wb_message */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbMessageDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息 ID，MESG_ 前缀 */
    private String messageId;
    /** 会话 ID */
    private String sessionId;
    /** 关联运行 ID，可空 */
    private String runId;
    /** 角色 user / assistant / tool / system */
    private String roleCode;
    /** 正文 */
    private String content;
    /** 估算 token 数 */
    private Integer tokenCount;
    private LocalDateTime createdAt;
    /** 逻辑删除 */
    private Boolean deleted;
}
