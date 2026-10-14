package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 知识库新建与更新入参 */
@Data
public class KbREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 知识库 ID，更新时必填 */
    private String kbId;
    @NotBlank(message = "知识库名不能为空")
    @Size(max = 128, message = "名称最长 128 字")
    private String kbName;
    @Size(max = 512, message = "描述最长 512 字")
    private String description;
}
