package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.domain.entity.WbEventDO;

import java.util.Map;
import java.util.Objects;

/** 运行事件与库表的双向转换：写入与回放共用一处口径，避免两侧字段漂移 */
public final class RunEventCodec {

    private RunEventCodec() {
    }

    /** 事件 DTO 转实体，eventId 每次生成，seq 保留原始序号用于回放 */
    public static WbEventDO toDO(String userId, RunEventDTO event) {
        WbEventDO entity = new WbEventDO();
        entity.setEventId(IdGenerator.generate(IdPrefixConst.EVENT));
        entity.setUserId(userId);
        entity.setRunId(event.runId());
        entity.setSessionId(event.sessionId());
        entity.setSeq((int) event.seq());
        entity.setEventType(event.eventType());
        entity.setBlockType(event.blockType());
        entity.setTextDelta(event.textDelta());
        entity.setPayload(JSONUtil.toJsonStr(event.payload()));
        entity.setOccurredAt(LocalDateTimeUtil.of(event.occurredAt()));
        return entity;
    }

    /** 实体转事件 DTO，payload 非法时退化为空对象而不是抛异常打断回放 */
    @SuppressWarnings("unchecked")
    public static RunEventDTO toDTO(WbEventDO entity) {
        Map<String, Object> payload = StrUtil.isBlank(entity.getPayload())
                ? Map.of()
                : JSONUtil.toBean(entity.getPayload(), Map.class);
        return new RunEventDTO(entity.getRunId(), entity.getSessionId(),
                entity.getSeq(), entity.getEventType(), entity.getBlockType(), entity.getTextDelta(),
                payload,
                Objects.nonNull(entity.getOccurredAt())
                        ? LocalDateTimeUtil.toEpochMilli(entity.getOccurredAt()) : 0L);
    }
}
