package com.liteworkbench.common;

import cn.hutool.core.date.LocalDateTimeUtil;

import java.time.LocalDateTime;
import java.util.Objects;

/** 时间转换：可空时间与对外毫秒时间戳互转，输出口径唯一定义处 */
public final class TimeUtil {

    private TimeUtil() {
    }

    /** 可空时间转毫秒时间戳，空返回 null */
    public static Long toMillis(LocalDateTime time) {
        return Objects.isNull(time) ? null : LocalDateTimeUtil.toEpochMilli(time);
    }
}
