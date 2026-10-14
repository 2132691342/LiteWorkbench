package com.liteworkbench.domain.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/** 会话实体，映射 wb_session */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class WbSessionDO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话 ID，SESS_ 前缀 */
    private String sessionId;
    /** 归属用户 */
    private String userId;
    private String title;
    /** 供应方编码，空则用默认供应方 */
    private String providerCode;
    /** 模型名，空则用供应方默认模型 */
    private String modelName;
    private String systemPrompt;
    private Integer messageCount;
    private Integer runCount;
    private Long totalTokens;
    private LocalDateTime lastActiveAt;
    /** 业务状态 normal / disabled */
    private String state;
    private String createdBy;
    private LocalDateTime createdAt;
    private String updatedBy;
    private LocalDateTime updatedAt;
    /** 逻辑删除 */
    private Boolean deleted;
    private String remark;
}
