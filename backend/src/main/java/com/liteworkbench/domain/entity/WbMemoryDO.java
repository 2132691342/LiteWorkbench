package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 长期记忆表映射：用户显式沉淀的事实与偏好，同 key 唯一 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbMemoryDO implements Serializable {

    private String memoryId;
    /** 归属用户 */
    private String userId;
    /** 记忆键，同用户内唯一 */
    private String memoryKey;
    private String memoryValue;
    /** 维度：fact / preference / constraint / profile */
    private String category;
    /** 权重，越大越优先注入 */
    private Integer weight;
    private Integer hitCount;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String state;
    private String remark;
}
