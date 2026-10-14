package com.liteworkbench.domain.vo;

import lombok.Data;

/** 调用链路片段：一次运行内的一段时间区间，供瀑布图按偏移与宽度绘制 */
@Data
public class TraceSpanVO {

    /** 片段标识，模型段为 model-{seq}，工具段为工具调用 ID */
    private String spanId;
    /** 片段类型：model / tool / plan */
    private String kind;
    /** 展示名：模型名或工具名 */
    private String name;
    /** 循环轮次，模型段为第几轮推理 */
    private Integer iteration;
    /** 相对运行开始的偏移毫秒 */
    private Long offsetMs;
    /** 持续毫秒 */
    private Long durationMs;
    /** 状态：ok / error / pending */
    private String status;
    /** 模型段输入 token */
    private Long inputTokens;
    /** 模型段输出 token */
    private Long outputTokens;
    /** 工具段输出字符数 */
    private Integer outputChars;
    /** 工具段入参摘要 */
    private String arguments;
}
