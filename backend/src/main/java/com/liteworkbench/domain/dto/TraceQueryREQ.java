package com.liteworkbench.domain.dto;

import lombok.Data;

import java.time.Duration;
import java.time.LocalDateTime;

/** 可观测性查询条件：时间窗口是可选的，缺省回看最近若干天 */
@Data
public class TraceQueryREQ {

    /** 起始时间，毫秒时间戳，0 或缺省表示按 days 回推 */
    private Long from;
    /** 结束时间，毫秒时间戳，0 或缺省表示不设上限 */
    private Long to;
    /** 缺省回看天数，仅当 from 未给出时生效 */
    private Integer days;

    /** 归一化起始时间：未给出时按回看天数回推 */
    public Long resolvedFrom(int defaultDays) {
        if (from != null && from > 0) {
            return from;
        }
        int window = days == null || days <= 0 ? defaultDays : days;
        return LocalDateTime.now().minus(Duration.ofDays(window))
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
    }
}
