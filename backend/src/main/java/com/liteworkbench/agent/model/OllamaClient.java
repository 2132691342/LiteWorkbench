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
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.util.Collections;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Ollama 本地协议出站：NDJSON 一行一帧，免密钥，工具调用不带 id */
@Slf4j
public class OllamaClient implements ModelClient {

    private static final String PROTOCOL = "ollama";

    private final ProviderAIConfig config;

    public OllamaClient(ProviderAIConfig config) {
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
        HttpURLConnection connection = null;
        try {
            connection = HttpModelSupport.open(endpoint(), buildBody(request).toString(), Collections.emptyMap(),
                    config.getTimeout(), readTimeout(request));
            HttpModelSupport.readLines(connection, line -> {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                String data = line.trim();
                if (data.isEmpty()) {
                    return true;
                }
                return consume(data, listener, result, text, thinking);
            });
        } catch (IOException e) {
            throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED, StrUtil.format("模型调用失败 {}", e.getMessage()));
        } finally {
            HttpModelSupport.close(connection);
        }
        result.setText(text.toString());
        result.setThinking(thinking.toString());
        return result;
    }

    /** 解析单行 NDJSON，返回 false 表示 done */
    private boolean consume(String data, ModelEventListener listener, StreamResult result,
                            StringBuilder text, StringBuilder thinking) {
        JSONObject frame;
        try {
            frame = JSONUtil.parseObj(data);
        } catch (Exception e) {
            log.warn("[model.ollama] 增量解析失败 {}", HttpModelSupport.truncate(data));
            return true;
        }
        if (StrUtil.isNotBlank(frame.getStr("error"))) {
            throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED,
                    StrUtil.format("模型返回错误 {}", HttpModelSupport.truncate(frame.getStr("error"))));
        }
        JSONObject message = frame.getJSONObject("message");
        if (Objects.nonNull(message)) {
            String reasoning = message.getStr("thinking");
            if (StrUtil.isNotEmpty(reasoning)) {
                thinking.append(reasoning);
                listener.onThinking(reasoning);
            }
            String content = message.getStr("content");
            if (StrUtil.isNotEmpty(content)) {
                text.append(content);
                listener.onText(content);
            }
            JSONArray toolCalls = message.getJSONArray("tool_calls");
            if (Objects.nonNull(toolCalls)) {
                for (int i = 0; i < toolCalls.size(); i++) {
                    ToolCall call = toToolCall(toolCalls.getJSONObject(i));
                    if (Objects.nonNull(call) && StrUtil.isNotBlank(call.getName())) {
                        result.getToolCalls().add(call);
                        listener.onToolCall(call);
                    }
                }
            }
        }
        if (frame.getBool("done", Boolean.FALSE)) {
            Usage usage = new Usage(frame.getLong("prompt_eval_count", 0L), frame.getLong("eval_count", 0L));
            result.setUsage(usage);
            listener.onUsage(usage);
            result.setFinishReason(frame.getStr("done_reason"));
            return false;
        }
        return true;
    }

    /** Ollama 工具参数是对象而非字符串，统一序列化为字符串交给内核 */
    private ToolCall toToolCall(JSONObject raw) {
        if (Objects.isNull(raw)) {
            return null;
        }
        JSONObject function = raw.getJSONObject("function");
        if (Objects.isNull(function)) {
            return null;
        }
        Object arguments = function.get("arguments");
        String argumentsJson = Objects.isNull(arguments) ? "{}" : JSONUtil.toJsonStr(arguments);
        return new ToolCall(UUID.randomUUID().toString(), function.getStr("name"), argumentsJson);
    }

    /** 请求体：messages + tools + options，工具结果消息用 tool 角色与 tool_name */
    private JSONObject buildBody(Request request) {
        JSONObject body = JSONUtil.createObj();
        body.put("model", config.getModel());
        body.put("stream", true);
        JSONArray messages = JSONUtil.createArray();
        for (Msg message : request.getMessages()) {
            JSONObject json = JSONUtil.createObj().put("role", message.getRole())
                    .put("content", StrUtil.nullToEmpty(message.getContent()));
            if (StrUtil.isNotBlank(message.getName())) {
                json.put("tool_name", message.getName());
            }
            if (Objects.nonNull(message.getToolCalls()) && !message.getToolCalls().isEmpty()) {
                JSONArray calls = JSONUtil.createArray();
                for (ToolCall call : message.getToolCalls()) {
                    calls.put(JSONUtil.createObj().put("function", JSONUtil.createObj()
                            .put("name", call.getName())
                            .put("arguments", parseArguments(call.getArguments()))));
                }
                json.put("tool_calls", calls);
            }
            messages.put(json);
        }
        body.put("messages", messages);
        if (Objects.nonNull(request.getTools()) && !request.getTools().isEmpty()) {
            JSONArray tools = JSONUtil.createArray();
            for (ToolSpec spec : request.getTools()) {
                tools.put(JSONUtil.createObj().put("type", "function").put("function", JSONUtil.createObj()
                        .put("name", spec.getName())
                        .put("description", StrUtil.nullToEmpty(spec.getDescription()))
                        .put("parameters", Objects.isNull(spec.getParameters())
                                ? defaultSchema() : spec.getParameters())));
            }
            body.put("tools", tools);
        }
        JSONObject options = JSONUtil.createObj();
        if (Objects.nonNull(request.getTemperature())) {
            options.put("temperature", request.getTemperature());
        }
        if (Objects.nonNull(request.getMaxTokens()) && request.getMaxTokens() > 0) {
            options.put("num_predict", request.getMaxTokens());
        }
        if (!options.isEmpty()) {
            body.put("options", options);
        }
        return body;
    }

    private JSONObject parseArguments(String arguments) {
        if (StrUtil.isBlank(arguments)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(arguments);
        } catch (Exception e) {
            return JSONUtil.createObj();
        }
    }

    private int readTimeout(Request request) {
        return Objects.nonNull(request.getTimeoutSeconds()) && request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() * 1000 : config.getReadTimeout();
    }

    private String endpoint() {
        String base = StrUtil.removeSuffix(StrUtil.nullToEmpty(config.getApiUrl()), "/");
        if (base.endsWith("/api/chat")) {
            return base;
        }
        return base + "/api/chat";
    }

    private JSONObject defaultSchema() {
        return JSONUtil.createObj().put("type", "object").put("properties", JSONUtil.createObj());
    }
}
