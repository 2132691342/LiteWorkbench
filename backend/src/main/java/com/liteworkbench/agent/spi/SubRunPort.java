package com.liteworkbench.agent.spi;

/** 子运行端口：父运行把一段子任务交给独立运行执行，由 service 侧负责编排与账目 */
public interface SubRunPort {

    /** 派发一次子运行并等待产出：子运行以 relation=child 挂在父运行下，超时或失败返回 ERROR: 开头的说明 */
    String delegate(String userId, String parentRunId, String sessionId, String task, long timeoutSeconds);
}
