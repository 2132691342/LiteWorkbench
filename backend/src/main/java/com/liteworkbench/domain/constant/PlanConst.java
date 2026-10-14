package com.liteworkbench.domain.constant;

/** 任务规划（规划器）静态常量：解析规则与容量边界 */
public interface PlanConst {

    /** Agent 规划工具名，与 wb_tool 种子一致 */
    String TOOL_NAME = "task_plan";
    /** 子任务委派工具名，与 wb_tool 种子一致 */
    String TOOL_DELEGATE = "task_delegate";

    /** 计划步数上限，超出截断——计划是给人看的短清单，不是任务清单文件 */
    int MAX_STEPS = 20;
    /** 单步描述长度上限 */
    int MAX_STEP_CHARS = 200;

    /** 已完成标记 */
    String MARK_DONE = "[x]";
    /** 进行中标记 */
    String MARK_DOING = "[~]";

    /** 步骤状态码 */
    interface Status {
        /** 未开始 */
        String PENDING = "pending";
        /** 进行中 */
        String DOING = "doing";
        /** 已完成 */
        String DONE = "done";
    }

    /** 子运行的默认等待上限，秒 */
    long DELEGATE_TIMEOUT_SECONDS = 180L;
}
