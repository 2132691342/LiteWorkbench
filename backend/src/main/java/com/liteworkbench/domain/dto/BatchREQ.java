package com.liteworkbench.domain.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/** 批量操作请求：一次最多 AppConst.MAX_BATCH_SIZE 条，逐条结果在响应里对账 */
@Data
public class BatchREQ implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 目标资源 ID 列表，去重由服务端做 */
    @NotEmpty(message = "批量操作至少选择一项")
    @Size(max = 100, message = "单次批量操作不能超过 100 项")
    private List<String> ids;
}
