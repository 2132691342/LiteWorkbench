package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.experimental.Accessors;

import java.io.Serial;
import java.io.Serializable;

/** 工作区新建与更新入参 */
@Data
@Accessors(chain = true)
public class WorkspaceREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 工作区 ID，更新与删除时必填 */
    private String workspaceId;
    @NotBlank(message = "工作区名称不能为空")
    @Size(max = 64, message = "工作区名称最长 64 字")
    private String workspaceName;
    @Size(max = 512, message = "描述最长 512 字")
    private String description;
}
