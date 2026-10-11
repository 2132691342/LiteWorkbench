package com.liteworkbench.agent;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 单次运行内工具输出捕获：工具实现写入，事件适配器读取 */
public class ToolOutputCapture {

    /** toolCallName -> 最近一次输出 */
    private final Map<String, String> outputs = new ConcurrentHashMap<>();

    /** 记录工具输出 */
    public void record(String toolName, String output) {
        if (Objects.nonNull(toolName)) {
            outputs.put(toolName, Objects.toString(output, ""));
        }
    }

    /** 读取并消费工具输出，未捕获返回空串，避免同一工具多次调用读到上一轮输出 */
    public String take(String toolName) {
        String output = outputs.remove(toolName);
        return Objects.toString(output, "");
    }
}
