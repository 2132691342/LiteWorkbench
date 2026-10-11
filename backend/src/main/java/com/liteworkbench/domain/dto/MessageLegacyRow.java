package com.liteworkbench.domain.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 旧版 wb_message 行的临时载体，仅迁移链路使用 */
@Data
public class MessageLegacyRow implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String messageId;
    private String userId;
    private String sessionId;
    private String runId;
    private String roleCode;
    private String content;
    private Integer tokenCount;
    private LocalDateTime createdAt;
}
