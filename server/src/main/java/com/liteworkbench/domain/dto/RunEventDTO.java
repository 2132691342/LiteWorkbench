package com.liteworkbench.domain.dto;

import java.util.Map;

/** 归一化运行事件：落库与 SSE 推送的统一载体 */
public record RunEventDTO(
        String runId,
        String sessionId,
        long seq,
        String eventType,
        String blockType,
        String textDelta,
        Map<String, Object> payload,
        long occurredAt) {
}
