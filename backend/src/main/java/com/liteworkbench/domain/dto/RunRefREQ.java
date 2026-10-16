package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 运行期引用入参：输入框里 @ 选中的上下文对象，类型见 RunRefTypeEnum */
@Data
public class RunRefREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 引用类型码：kb / file / doc */
    @NotBlank(message = "引用类型不能为空")
    private String type;
    /** 引用对象 ID */
    @NotBlank(message = "引用对象不能为空")
    @Size(max = 64, message = "引用对象 ID 非法")
    private String id;
    /** 展示名，回显与日志用，不参与检索 */
    @Size(max = 128, message = "引用名过长")
    private String label;
}
