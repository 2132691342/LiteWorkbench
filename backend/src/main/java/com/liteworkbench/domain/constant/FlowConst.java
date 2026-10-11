package com.liteworkbench.domain.constant;

/** 工作流静态常量：节点类型、结构边界、默认超时 */
public interface FlowConst {

    /** 单画布节点数上限 */
    int MAX_NODE_COUNT = 50;

    /** python 节点默认超时（秒） */
    int PYTHON_TIMEOUT_SECONDS = 120;

    /** python 节点超时上限（秒） */
    int PYTHON_TIMEOUT_MAX_SECONDS = 600;

    /** 单节点输出写入上下文的裁剪上限（字符） */
    int NODE_OUTPUT_MAX = 20_000;

    /** 运行中节点输出写入事件表的摘要上限（字符） */
    int NODE_EVENT_OUTPUT_MAX = 2_000;
}
