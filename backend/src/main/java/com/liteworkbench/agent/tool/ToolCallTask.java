package com.liteworkbench.agent.tool;

/** 一次工具任务：模型给出的调用名与参数原文，供批量并发执行按下标保序 */
public record ToolCallTask(String name, String arguments) {
}
