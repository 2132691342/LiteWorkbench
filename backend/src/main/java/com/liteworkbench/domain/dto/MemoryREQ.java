package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 长期记忆请求：查询复用分页字段，新增与修改共用一个结构 */
@Data
@EqualsAndHashCode(callSuper = true)
public class MemoryREQ extends PageREQ {

    /** 记忆 ID，修改时必填 */
    @Size(max = 40, message = "记忆标识长度不合法")
    private String memoryId;

    /** 记忆键，短且可辨识 */
    @Size(max = 128, message = "记忆键最长 128 字符")
    private String memoryKey;

    @Size(max = 2000, message = "记忆内容最长 2000 字符")
    private String memoryValue;

    /** 维度：fact / preference / constraint / profile */
    @Size(max = 32, message = "记忆维度长度不合法")
    private String category;

    /** 权重 0-9，越大越优先注入 */
    @Min(value = 0, message = "权重不能小于 0")
    @Max(value = 9, message = "权重不能大于 9")
    private Integer weight;
}
