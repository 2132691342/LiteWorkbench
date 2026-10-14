package com.liteworkbench.agent.model;

import cn.hutool.json.JSONObject;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 模型层传输对象：消息、工具调用、工具描述、请求与响应，三协议共用同一结构 */
public final class ModelTypes {

    private ModelTypes() {
    }

    /** 消息角色常量 */
    public interface Role {
        String SYSTEM = "system";
        String USER = "user";
        String ASSISTANT = "assistant";
        String TOOL = "tool";
    }

    /** 对话消息：正文、工具调用与工具结果复用同一结构，toolCallId 非空即为工具结果消息 */
    @Data
    public static class Msg {

        private String role;
        private String content;
        private String toolCallId;
        private String name;
        private List<ToolCall> toolCalls = new ArrayList<>();

        public Msg() {
        }

        public Msg(String role, String content) {
            this.role = role;
            this.content = content;
        }

        /** 系统消息 */
        public static Msg system(String content) {
            return new Msg(Role.SYSTEM, content);
        }

        /** 用户消息 */
        public static Msg user(String content) {
            return new Msg(Role.USER, content);
        }

        /** 助手消息，可带工具调用 */
        public static Msg assistant(String content, List<ToolCall> toolCalls) {
            Msg msg = new Msg(Role.ASSISTANT, content);
            msg.setToolCalls(Objects.isNull(toolCalls) ? new ArrayList<>() : new ArrayList<>(toolCalls));
            return msg;
        }

        /** 工具结果消息 */
        public static Msg tool(String toolCallId, String name, String content) {
            Msg msg = new Msg(Role.TOOL, content);
            msg.setToolCallId(toolCallId);
            msg.setName(name);
            return msg;
        }
    }

    /** 模型要求调用的一次工具 */
    @Data
    public static class ToolCall {

        private String id;
        private String name;
        /** 参数为模型给出的 JSON 字符串原文 */
        private String arguments;

        public ToolCall() {
        }

        public ToolCall(String id, String name, String arguments) {
            this.id = id;
            this.name = name;
            this.arguments = arguments;
        }
    }

    /** 下发给模型的工具描述 */
    @Data
    public static class ToolSpec {

        private String name;
        private String description;
        /** JSON Schema 对象 */
        private JSONObject parameters;

        public ToolSpec() {
        }

        public ToolSpec(String name, String description, JSONObject parameters) {
            this.name = name;
            this.description = description;
            this.parameters = parameters;
        }
    }

    /** 一次模型请求 */
    @Data
    public static class Request {

        private List<Msg> messages = new ArrayList<>();
        private List<ToolSpec> tools = new ArrayList<>();
        private Double temperature;
        private Integer maxTokens;
        private Integer timeoutSeconds;
    }

    /** Token 用量 */
    @Data
    public static class Usage {

        private long inputTokens;
        private long outputTokens;

        public Usage() {
        }

        public Usage(long inputTokens, long outputTokens) {
            this.inputTokens = inputTokens;
            this.outputTokens = outputTokens;
        }
    }

    /** 一次流式调用的汇总结果 */
    @Data
    public static class StreamResult {

        private String text = "";
        private String thinking = "";
        private List<ToolCall> toolCalls = new ArrayList<>();
        private Usage usage = new Usage();
        private String finishReason;
    }
}
