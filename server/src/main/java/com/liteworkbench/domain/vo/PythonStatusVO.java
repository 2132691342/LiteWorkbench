package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** Python 运行时探测结果视图 */
@Data
public class PythonStatusVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 状态 READY / NOT_FOUND / BAD_VERSION */
    private String status;
    /** 探测到的版本，如 3.12.1 */
    private String version;
    /** 解释器来源路径 */
    private String source;
    /** 版本是否满足最低要求 */
    private Boolean ok;
}
