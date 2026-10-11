package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 用户设置更新入参 */
@Data
public class SettingREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设置键 */
    @NotBlank(message = "设置键不能为空")
    private String settingKey;
    /** 设置值 */
    private String settingValue;
}
