package com.liteworkbench.agent;

import com.liteworkbench.domain.dto.RunEventDTO;

import java.util.function.Consumer;

/** Agent 运行时门面：异步发起一次运行，事件按产生顺序回调，返回可取消句柄 */
public interface AgentRuntime {

    /**
     * 发起一次运行。实现必须立即返回，事件在后台线程按序回调；取消经句柄传播到模型连接与工具执行。
     *
     * @param context 本次运行的全部装配信息
     * @param sink    事件出口，实现内已分配 seq，调用方只负责落库与推送
     * @return 运行句柄
     */
    RunHandle start(RunContext context, Consumer<RunEventDTO> sink);
}
