package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/** 页面手工执行工具入参 */
@Data
public class InvokeToolREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工具参数 */
    @NotNull(message = "参数不能为空")
    private Map<String, Object> args;
    /** confirm 级工具必须显式确认为 true */
    private Boolean confirmed = false;
    /** 执行超时秒数，可空走默认 */
    private Integer timeoutSeconds;
}
