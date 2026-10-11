package com.liteworkbench.domain.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/** AI 辅助写作结果 */
@Data
public class DocAssistVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 执行的动作 */
    private String action;
    /** 模型产出的纯文本 */
    private String text;
    /** 耗时（毫秒） */
    private Long durationMs;
}
