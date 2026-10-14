package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 角色授权菜单请求 */
@Data
public class RoleGrantREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @NotBlank(message = "角色 ID 不能为空")
    private String roleId;
    /** 授权菜单 ID 全量集合，空表示清空授权 */
    private List<String> menuIds;
}
