package com.liteworkbench.domain.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 会话与运行分页查询入参，状态与时间范围仅运行记录页使用 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionPageREQ extends PageREQ {

    /** 会话 ID，精确过滤 */
    private String sessionId;
    /** 运行状态过滤：queued / running / success / failed / aborted */
    private String state;
    /** 起始时间（毫秒时间戳），可空 */
    private Long from;
    /** 结束时间（毫秒时间戳），可空 */
    private Long to;
}
