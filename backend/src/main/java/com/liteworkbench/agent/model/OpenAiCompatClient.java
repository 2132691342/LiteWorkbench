package com.liteworkbench.agent.model;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.Request;
import com.liteworkbench.agent.model.ModelTypes.StreamResult;
import com.liteworkbench.agent.model.ModelTypes.ToolCall;
import com.liteworkbench.agent.model.ModelTypes.ToolSpec;
import com.liteworkbench.agent.model.ModelTypes.Usage;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** OpenAI 兼容协议出站：SSE `data:` 行 + `[DONE]` 终止，覆盖 DeepSeek / GLM / Kimi / vLLM / OneAPI */
@Slf4j
public class OpenAiCompatClient implements ModelClient {

    private static final String PROTOCOL = "openai";
    private static final String DONE = "[DONE]";
    private static final String DATA_PREFIX = "data:";

    private final ProviderAIConfig config;

    public OpenAiCompatClient(ProviderAIConfig config) {
        this.config = config;
    }

    @Override
    public String protocol() {
        return PROTOCOL;
    }

    @Override
    public String modelName() {
        return config.getModel();
    }

    @Override
    public StreamResult stream(Request request, ModelEventListener listener, BooleanSupplier cancelled) {
        StreamResult result = new StreamResult();
        StringBuilder text = new StringBuilder();
        StringBuilder thinking = new StringBuilder();
        Map<Integer, PendingCall> pending = new LinkedHashMap<>();
        HttpURLConnection connection = null;
        try {
            connection = HttpModelSupport.open(endpoint(), buildBody(request).toString(), headers(),
                    config.getTimeout(), readTimeout(request));
            HttpURLConnection current = connection;
            HttpModelSupport.readLines(current, line -> {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                String data = payloadOf(line);
                if (Objects.isNull(data)) {
                    return true;
                }
                if (DONE.equals(data)) {
                    return false;
                }
                consume(data, listener, result, text, thinking, pending);
                return true;
            });
        } catch (IOException e) {
            throw HttpModelSupport.ioFailure(e);
        } finally {
            HttpModelSupport.close(connection);
        }
        result.setText(text.toString());
        result.setThinking(thinking.toString());
        pending.values().stream().map(PendingCall::toCall)
                .filter(call -> StrUtil.isNotBlank(call.getName()))
                .forEach(call -> {
                    result.getToolCalls().add(call);
                    listener.onToolCall(call);
                });
        return result;
    }

    /** 解析单帧增量：正文 / 思考 / 工具调用分片 */
    private void consume(String data, ModelEventListener listener, StreamResult result,
                         StringBuilder text, StringBuilder thinking, Map<Integer, PendingCall> pending) {
        JSONObject chunk;
        try {
            chunk = JSONUtil.parseObj(data);
        } catch (Exception e) {
            log.warn("[model.openai] 增量解析失败 {}", HttpModelSupport.truncate(data));
            return;
        }
        JSONObject usage = chunk.getJSONObject("usage");
        if (Objects.nonNull(usage)) {
            Usage counted = new Usage(usage.getLong("prompt_tokens", 0L), usage.getLong("completion_tokens", 0L));
            result.setUsage(counted);
            listener.onUsage(counted);
        }
        JSONArray choices = chunk.getJSONArray("choices");
        if (Objects.isNull(choices) || choices.isEmpty()) {
            return;
        }
        JSONObject choice = choices.getJSONObject(0);
        String finish = choice.getStr("finish_reason");
        if (StrUtil.isNotBlank(finish)) {
            result.setFinishReason(finish);
        }
        JSONObject delta = choice.getJSONObject("delta");
        if (Objects.isNull(delta)) {
            return;
        }
        String reasoning = delta.getStr("reasoning_content");
        if (StrUtil.isNotEmpty(reasoning)) {
            thinking.append(reasoning);
            listener.onThinking(reasoning);
        }
        String content = delta.getStr("content");
        if (StrUtil.isNotEmpty(content)) {
            text.append(content);
            listener.onText(content);
        }
        JSONArray toolCalls = delta.getJSONArray("tool_calls");
        if (Objects.isNull(toolCalls)) {
            return;
        }
        for (int i = 0; i < toolCalls.size(); i++) {
            JSONObject fragment = toolCalls.getJSONObject(i);
            if (Objects.isNull(fragment)) {
                continue;
            }
            int index = fragment.getInt("index", i);
            pending.computeIfAbsent(index, key -> new PendingCall()).merge(fragment);
        }
    }

    /** 请求体：消息 + 工具 + 采样参数，显式开启用量回传 */
    private JSONObject buildBody(Request request) {
        JSONObject body = JSONUtil.createObj();
        body.put("model", config.getModel());
        body.put("stream", true);
        JSONArray messages = JSONUtil.createArray();
        for (Msg message : request.getMessages()) {
            messages.put(toMessageJson(message));
        }
        body.put("messages", messages);
        if (Objects.nonNull(request.getTools()) && !request.getTools().isEmpty()) {
            JSONArray tools = JSONUtil.createArray();
            for (ToolSpec spec : request.getTools()) {
                JSONObject function = JSONUtil.createObj()
                        .put("name", spec.getName())
                        .put("description", StrUtil.nullToEmpty(spec.getDescription()))
                        .put("parameters", Objects.isNull(spec.getParameters())
                                ? defaultSchema() : spec.getParameters());
                tools.put(JSONUtil.createObj().put("type", "function").put("function", function));
            }
            body.put("tools", tools);
            body.put("tool_choice", "auto");
        }
        applySampling(body, request);
        body.put("stream_options", JSONUtil.createObj().put("include_usage", true));
        return body;
    }

    /** 单条消息转协议结构，工具结果消息带 tool_call_id */
    private JSONObject toMessageJson(Msg message) {
        JSONObject json = JSONUtil.createObj().put("role", message.getRole());
        if (StrUtil.isNotBlank(message.getToolCallId())) {
            return json.put("tool_call_id", message.getToolCallId())
                    .put("content", StrUtil.nullToEmpty(message.getContent()));
        }
        json.put("content", StrUtil.nullToEmpty(message.getContent()));
        if (Objects.nonNull(message.getToolCalls()) && !message.getToolCalls().isEmpty()) {
            JSONArray calls = JSONUtil.createArray();
            for (ToolCall call : message.getToolCalls()) {
                calls.put(JSONUtil.createObj()
                        .put("id", call.getId())
                        .put("type", "function")
                        .put("function", JSONUtil.createObj()
                                .put("name", call.getName())
                                .put("arguments", StrUtil.nullToEmpty(call.getArguments()))));
            }
            json.put("tool_calls", calls);
        }
        return json;
    }

    private void applySampling(JSONObject body, Request request) {
        if (Objects.nonNull(request.getTemperature())) {
            body.put("temperature", request.getTemperature());
        }
        if (Objects.nonNull(request.getMaxTokens()) && request.getMaxTokens() > 0) {
            body.put("max_tokens", request.getMaxTokens());
        }
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        if (StrUtil.isNotBlank(config.getApiKey())) {
            headers.put("Authorization", "Bearer " + config.getApiKey());
        }
        return headers;
    }

    private int readTimeout(Request request) {
        return Objects.nonNull(request.getTimeoutSeconds()) && request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() * 1000 : config.getReadTimeout();
    }

    /** 端点补全：baseUrl 已含 /chat/completions 则原样使用 */
    private String endpoint() {
        String base = StrUtil.removeSuffix(StrUtil.nullToEmpty(config.getApiUrl()), "/");
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        return base + "/chat/completions";
    }

    private JSONObject defaultSchema() {
        return JSONUtil.createObj().put("type", "object").put("properties", JSONUtil.createObj());
    }

    /** 流式分片的工具调用累积器：按 index 合并 id / name / arguments */
    private static final class PendingCall {

        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        void merge(JSONObject fragment) {
            String fragmentId = fragment.getStr("id");
            if (StrUtil.isNotBlank(fragmentId)) {
                id = fragmentId;
            }
            JSONObject function = fragment.getJSONObject("function");
            if (Objects.isNull(function)) {
                return;
            }
            String fragmentName = function.getStr("name");
            if (StrUtil.isNotBlank(fragmentName)) {
                name = fragmentName;
            }
            String fragmentArguments = function.getStr("arguments");
            if (Objects.nonNull(fragmentArguments)) {
                arguments.append(fragmentArguments);
            }
        }

        ToolCall toCall() {
            String callId = StrUtil.isBlank(id) ? UUID.randomUUID().toString() : id;
            return new ToolCall(callId, name, arguments.toString());
        }
    }

    /** SSE 行取值：过滤注释与空行，剥 `data:` 前缀 */
    private String payloadOf(String line) {
        if (!line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String data = line.substring(DATA_PREFIX.length()).trim();
        return data.isEmpty() ? null : data;
    }
}
