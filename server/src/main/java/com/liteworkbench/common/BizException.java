package com.liteworkbench.common;

import lombok.Getter;

import java.io.Serial;

/** 业务异常，携带错误码与可选的格式化参数 */
@Getter
public class BizException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 错误码 */
    private final ErrorCodeEnum errorCode;

    public BizException(ErrorCodeEnum errorCode) {
        super(errorCode.getMessage());
        this.errorCode = errorCode;
    }

    /** 用 String.format 补齐提示中的占位符 */
    public BizException(ErrorCodeEnum errorCode, Object... args) {
        super(String.format(errorCode.getMessage(), args));
        this.errorCode = errorCode;
    }

    public BizException(ErrorCodeEnum errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }
}
