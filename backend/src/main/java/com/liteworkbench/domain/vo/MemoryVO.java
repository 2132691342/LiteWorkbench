package com.liteworkbench.domain.vo;

import lombok.Data;

/** 长期记忆视图对象 */
@Data
public class MemoryVO {

    private String memoryId;
    /** 记忆键，同用户内唯一 */
    private String memoryKey;
    private String memoryValue;
    /** 维度：fact / preference / constraint / profile */
    private String category;
    /** 权重 0-9 */
    private Integer weight;
    private Integer hitCount;
    /** 更新时间，毫秒时间戳 */
    private Long updatedAt;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
