package com.liteworkbench.domain.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

/** 会话分页查询入参 */
@Data
@EqualsAndHashCode(callSuper = true)
public class SessionPageREQ extends PageREQ {

    /** 会话 ID，精确过滤 */
    private String sessionId;
}
