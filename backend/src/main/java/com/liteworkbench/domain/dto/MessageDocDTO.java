package com.liteworkbench.domain.dto;

import java.io.Serializable;

/** 会话消息的 Manticore 文档模型，读写消息表的统一载体 */
public record MessageDocDTO(String messageId, String userId, String sessionId, String runId,
                            String role, String content, Integer tokenCount, Long ts) implements Serializable {
}
