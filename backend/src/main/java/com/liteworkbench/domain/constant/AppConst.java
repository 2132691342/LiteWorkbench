package com.liteworkbench.domain.constant;

/** 应用级静态常量：请求头、MDC 键、通用阈值 */
public interface AppConst {

    /** 链路标识请求头 */
    String HEADER_TRACE_ID = "X-Trace-Id";
    /** 鉴权请求头 */
    String HEADER_AUTHORIZATION = "Authorization";
    /** MDC 键：链路标识 */
    String MDC_TRACE_ID = "traceId";
    /** MDC 键：用户 ID */
    String MDC_USER_ID = "userId";
    /** MDC 键：会话 ID */
    String MDC_SESSION_ID = "sessionId";
    /** MDC 键：运行 ID */
    String MDC_RUN_ID = "runId";

    /** 默认页码 */
    int DEFAULT_PAGE = 1;
    /** 默认每页条数 */
    int DEFAULT_SIZE = 20;
    /** 单页最大条数 */
    int MAX_SIZE = 200;
}
