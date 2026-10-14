package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** 角色新建与修改请求 */
@Data
public class RoleREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 角色 ID，修改时必填 */
    private String roleId;
    /** 角色码，仅新建时使用 */
    @Pattern(regexp = "^[a-z][a-z0-9_]{2,31}$", message = "角色码须为小写字母开头，可含数字与下划线，长度 3-32")
    private String roleCode;
    @NotBlank(message = "角色名不能为空")
    @Size(max = 64, message = "角色名不能超过 64 字符")
    private String roleName;
    private Integer sortNo;
    private String remark;
}
