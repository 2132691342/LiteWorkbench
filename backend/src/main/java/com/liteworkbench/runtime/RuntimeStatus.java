package com.liteworkbench.runtime;

/** Python 探测结果 */
public record RuntimeStatus(String status, String version, String source, boolean ok) {

    /** 就绪 */
    public static final String READY = "READY";
    /** 未找到解释器 */
    public static final String NOT_FOUND = "NOT_FOUND";
    /** 版本过低 */
    public static final String BAD_VERSION = "BAD_VERSION";

    /** 最低版本要求 3.8 */
    public static final int MIN_VERSION = 8;
}
