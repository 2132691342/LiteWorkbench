package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 用户级键值设置实体，映射 wb_setting */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbSettingDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 设置 ID，SETG_ 前缀 */
    private String settingId;
    /** 归属用户 */
    private String userId;
    /** 设置键，用户内唯一 */
    private String settingKey;
    /** 设置值 */
    private String settingValue;
    /** 值类型 string / int / bool / json */
    private String valueType;
    private String updatedBy;
    private LocalDateTime updatedAt;
}
