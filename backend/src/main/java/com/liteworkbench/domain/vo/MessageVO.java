package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 会话消息视图对象 */
@Data
public class MessageVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 消息 ID */
    private String messageId;
    /** 关联运行 ID */
    private String runId;
    /** 角色 user / assistant */
    private String roleCode;
    /** 正文 */
    private String content;
    /** 发生时间，毫秒时间戳 */
    private Long createdAt;
}
