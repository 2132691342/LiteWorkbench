package com.liteworkbench.common;

import cn.hutool.core.lang.Assert;
import com.liteworkbench.domain.constant.AppConst;
import lombok.Data;
import org.slf4j.MDC;

import java.io.Serial;
import java.io.Serializable;

/** 统一响应信封：code 为 String，"0" 表示成功，非 "0" 为错误码 */
@Data
public class R<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 业务码，"0" 成功；String 类型与 HTTP 状态码的 int 区分 */
    private String code;
    private String message;
    private T data;
    /** 链路标识，与日志 traceId 一致 */
    private String requestId;
    /** 响应时间戳，毫秒 */
    private Long timestamp;

    private R(String code, String message, T data) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.requestId = MDC.get(AppConst.MDC_TRACE_ID);
        this.timestamp = System.currentTimeMillis();
    }

    /** 成功，带数据 */
    public static <T> R<T> ok(T data) {
        return new R<>(ErrorCodeEnum.SUCCESS.getCode(), ErrorCodeEnum.SUCCESS.getMessage(), data);
    }

    /** 成功，无数据 */
    public static <T> R<T> ok() {
        return ok(null);
    }

    /** 失败，按错误码构造 */
    public static <T> R<T> fail(ErrorCodeEnum errorCode) {
        Assert.notNull(errorCode, "errorCode must not be null");
        return new R<>(errorCode.getCode(), errorCode.getMessage(), null);
    }

    /** 失败，自定义提示 */
    public static <T> R<T> fail(ErrorCodeEnum errorCode, String message) {
        R<T> r = fail(errorCode);
        r.setMessage(message);
        return r;
    }
}
