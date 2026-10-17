package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 工作区视图对象：桶名对普通用户只读展示，管理操作走桶管理接口 */
@Data
public class WorkspaceVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String workspaceId;
    private String workspaceName;
    /** 绑定桶名 */
    private String bucketName;
    private String description;
    /** 是否默认工作区 */
    private Boolean isDefault;
    /** 桶内对象数，管理视角展示 */
    private Long objectCount;
    /** 桶内对象总字节数 */
    private Long totalSize;
    /** 创建时间，毫秒时间戳 */
    private Long createdAt;
}
