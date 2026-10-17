package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 会话视图对象 */
@Data
public class SessionVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 会话 ID */
    private String sessionId;
    /** 归属用户 */
    private String userId;
    /** 标题 */
    private String title;
    /** 供应方编码 */
    private String providerCode;
    /** 模型名 */
    private String modelName;
    /** 工作区 ID */
    private String workspaceId;
    /** 消息数 */
    private Integer messageCount;
    /** 运行数 */
    private Integer runCount;
    /** 累计 token */
    private Long totalTokens;
    /** 最近活跃时间，毫秒时间戳 */
    private Long lastActiveAt;
    /** 状态 */
    private String state;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
