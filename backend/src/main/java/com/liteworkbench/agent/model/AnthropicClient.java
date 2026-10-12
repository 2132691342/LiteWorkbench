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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Anthropic Messages 协议出站：system 独立、内容块增量、工具结果以 user 消息承载 */
@Slf4j
public class AnthropicClient implements ModelClient {

    private static final String PROTOCOL = "anthropic";
    private static final String DATA_PREFIX = "data:";
    private static final String API_VERSION = "2023-06-01";
    private static final int DEFAULT_MAX_TOKENS = 4096;

    /** 内容块类型 */
    private static final String BLOCK_TOOL_USE = "tool_use";
    private static final String BLOCK_THINKING = "thinking";
    /** 增量类型 */
    private static final String DELTA_TEXT = "text_delta";
    private static final String DELTA_THINKING = "thinking_delta";
    private static final String DELTA_JSON = "input_json_delta";

    private final ProviderAIConfig config;

    public AnthropicClient(ProviderAIConfig config) {
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
        Map<Integer, PendingBlock> blocks = new LinkedHashMap<>();
        HttpURLConnection connection = null;
        try {
            connection = HttpModelSupport.open(endpoint(), buildBody(request).toString(), headers(),
                    config.getTimeout(), readTimeout(request));
            HttpModelSupport.readLines(connection, line -> {
                if (cancelled.getAsBoolean()) {
                    return false;
                }
                String data = payloadOf(line);
                if (Objects.isNull(data)) {
                    return true;
                }
                return consume(data, listener, result, text, thinking, blocks);
            });
        } catch (IOException e) {
            throw HttpModelSupport.ioFailure(e);
        } finally {
            HttpModelSupport.close(connection);
        }
        result.setText(text.toString());
        result.setThinking(thinking.toString());
        blocks.values().stream().filter(PendingBlock::isToolUse)
                .map(PendingBlock::toCall)
                .filter(call -> StrUtil.isNotBlank(call.getName()))
                .forEach(call -> {
                    result.getToolCalls().add(call);
                    listener.onToolCall(call);
                });
        return result;
    }

    /** 解析单个 SSE 数据帧，返回 false 表示流已结束 */
    private boolean consume(String data, ModelEventListener listener, StreamResult result,
                            StringBuilder text, StringBuilder thinking, Map<Integer, PendingBlock> blocks) {
        JSONObject event;
        try {
            event = JSONUtil.parseObj(data);
        } catch (Exception e) {
            log.warn("[model.anthropic] 增量解析失败 {}", HttpModelSupport.truncate(data));
            return true;
        }
        String type = event.getStr("type");
        if (Objects.isNull(type)) {
            return true;
        }
        switch (type) {
            case "content_block_start" -> handleBlockStart(event, listener, blocks);
            case "content_block_delta" -> handleBlockDelta(event, listener, blocks, text, thinking);
            case "message_start" -> handleMessageStart(event, listener, result);
            case "message_delta" -> handleMessageDelta(event, listener, result);
            case "message_stop" -> {
                return false;
            }
            case "error" -> {
                JSONObject error = event.getJSONObject("error");
                String message = Objects.isNull(error) ? "unknown" : error.getStr("message");
                throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED,
                        StrUtil.format("模型返回错误 {}", HttpModelSupport.truncate(message)));
            }
            default -> {
                // ping / content_block_stop 等无需处理
            }
        }
        return true;
    }

    private void handleBlockStart(JSONObject event, ModelEventListener listener,
                                  Map<Integer, PendingBlock> blocks) {
        JSONObject block = event.getJSONObject("content_block");
        if (Objects.isNull(block)) {
            return;
        }
        int index = event.getInt("index", blocks.size());
        String blockType = block.getStr("type");
        if (BLOCK_TOOL_USE.equals(blockType)) {
            PendingBlock pending = new PendingBlock();
            pending.toolUse = true;
            pending.id = block.getStr("id");
            pending.name = block.getStr("name");
            blocks.put(index, pending);
            return;
        }
        if (BLOCK_THINKING.equals(blockType) && StrUtil.isNotEmpty(block.getStr("thinking"))) {
            listener.onThinking(block.getStr("thinking"));
        }
    }

    private void handleBlockDelta(JSONObject event, ModelEventListener listener,
                                  Map<Integer, PendingBlock> blocks, StringBuilder text, StringBuilder thinking) {
        JSONObject delta = event.getJSONObject("delta");
        if (Objects.isNull(delta)) {
            return;
        }
        String deltaType = delta.getStr("type");
        if (DELTA_TEXT.equals(deltaType)) {
            String chunk = delta.getStr("text");
            if (StrUtil.isNotEmpty(chunk)) {
                text.append(chunk);
                listener.onText(chunk);
            }
            return;
        }
        if (DELTA_THINKING.equals(deltaType)) {
            String chunk = delta.getStr("thinking");
            if (StrUtil.isNotEmpty(chunk)) {
                thinking.append(chunk);
                listener.onThinking(chunk);
            }
            return;
        }
        if (DELTA_JSON.equals(deltaType)) {
            int index = event.getInt("index", 0);
            PendingBlock pending = blocks.get(index);
            if (Objects.nonNull(pending) && StrUtil.isNotEmpty(delta.getStr("partial_json"))) {
                pending.arguments.append(delta.getStr("partial_json"));
            }
        }
    }

    private void handleMessageStart(JSONObject event, ModelEventListener listener, StreamResult result) {
        JSONObject message = event.getJSONObject("message");
        if (Objects.isNull(message)) {
            return;
        }
        JSONObject usage = message.getJSONObject("usage");
        if (Objects.nonNull(usage)) {
            Usage counted = new Usage(usage.getLong("input_tokens", 0L), result.getUsage().getOutputTokens());
            result.setUsage(counted);
            listener.onUsage(counted);
        }
    }

    private void handleMessageDelta(JSONObject event, ModelEventListener listener, StreamResult result) {
        JSONObject delta = event.getJSONObject("delta");
        if (Objects.nonNull(delta) && StrUtil.isNotBlank(delta.getStr("stop_reason"))) {
            result.setFinishReason(delta.getStr("stop_reason"));
        }
        JSONObject usage = event.getJSONObject("usage");
        if (Objects.nonNull(usage)) {
            Usage counted = new Usage(result.getUsage().getInputTokens(), usage.getLong("output_tokens", 0L));
            result.setUsage(counted);
            listener.onUsage(counted);
        }
    }

    /** 请求体：system 顶层、messages 只含 user / assistant、工具用 input_schema */
    private JSONObject buildBody(Request request) {
        JSONObject body = JSONUtil.createObj();
        body.put("model", config.getModel());
        body.put("stream", true);
        body.put("max_tokens", resolveMaxTokens(request));
        StringBuilder system = new StringBuilder();
        JSONArray messages = JSONUtil.createArray();
        for (Msg message : request.getMessages()) {
            if (ModelTypes.Role.SYSTEM.equals(message.getRole())) {
                system.append(StrUtil.nullToEmpty(message.getContent())).append('\n');
                continue;
            }
            JSONObject converted = toMessageJson(message);
            if (Objects.nonNull(converted)) {
                messages.put(converted);
            }
        }
        if (system.length() > 0) {
            body.put("system", system.toString().trim());
        }
        body.put("messages", messages);
        if (Objects.nonNull(request.getTools()) && !request.getTools().isEmpty()) {
            JSONArray tools = JSONUtil.createArray();
            for (ToolSpec spec : request.getTools()) {
                tools.put(JSONUtil.createObj()
                        .put("name", spec.getName())
                        .put("description", StrUtil.nullToEmpty(spec.getDescription()))
                        .put("input_schema", Objects.isNull(spec.getParameters())
                                ? defaultSchema() : spec.getParameters()));
            }
            body.put("tools", tools);
        }
        if (Objects.nonNull(request.getTemperature())) {
            body.put("temperature", request.getTemperature());
        }
        return body;
    }

    /** 工具结果转 user 消息的 tool_result 块，工具调用转 assistant 消息的 tool_use 块 */
    private JSONObject toMessageJson(Msg message) {
        if (StrUtil.isNotBlank(message.getToolCallId())) {
            JSONArray blocks = JSONUtil.createArray();
            blocks.put(JSONUtil.createObj()
                    .put("type", "tool_result")
                    .put("tool_use_id", message.getToolCallId())
                    .put("content", StrUtil.nullToEmpty(message.getContent())));
            return JSONUtil.createObj().put("role", ModelTypes.Role.USER).put("content", blocks);
        }
        if (Objects.nonNull(message.getToolCalls()) && !message.getToolCalls().isEmpty()) {
            JSONArray blocks = JSONUtil.createArray();
            if (StrUtil.isNotEmpty(message.getContent())) {
                blocks.put(JSONUtil.createObj().put("type", "text").put("text", message.getContent()));
            }
            for (ToolCall call : message.getToolCalls()) {
                blocks.put(JSONUtil.createObj()
                        .put("type", BLOCK_TOOL_USE)
                        .put("id", call.getId())
                        .put("name", call.getName())
                        .put("input", parseArguments(call.getArguments())));
            }
            return JSONUtil.createObj().put("role", ModelTypes.Role.ASSISTANT).put("content", blocks);
        }
        if (StrUtil.isEmpty(message.getContent())) {
            return null;
        }
        return JSONUtil.createObj().put("role", message.getRole())
                .put("content", StrUtil.nullToEmpty(message.getContent()));
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

    private int resolveMaxTokens(Request request) {
        if (Objects.nonNull(request.getMaxTokens()) && request.getMaxTokens() > 0) {
            return request.getMaxTokens();
        }
        Object configured = config.getAdditionalConfigByKey("max_tokens");
        if (configured instanceof Number number && number.intValue() > 0) {
            return number.intValue();
        }
        return DEFAULT_MAX_TOKENS;
    }

    private Map<String, String> headers() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", StrUtil.nullToEmpty(config.getApiKey()));
        headers.put("anthropic-version", API_VERSION);
        return headers;
    }

    private int readTimeout(Request request) {
        return Objects.nonNull(request.getTimeoutSeconds()) && request.getTimeoutSeconds() > 0
                ? request.getTimeoutSeconds() * 1000 : config.getReadTimeout();
    }

    private String endpoint() {
        String base = StrUtil.removeSuffix(StrUtil.nullToEmpty(config.getApiUrl()), "/");
        if (base.endsWith("/messages")) {
            return base;
        }
        return base + "/v1/messages";
    }

    private JSONObject defaultSchema() {
        return JSONUtil.createObj().put("type", "object").put("properties", JSONUtil.createObj());
    }

    private String payloadOf(String line) {
        if (!line.startsWith(DATA_PREFIX)) {
            return null;
        }
        String data = line.substring(DATA_PREFIX.length()).trim();
        return data.isEmpty() ? null : data;
    }

    /** 内容块累积器：tool_use 块收集 id / name / 参数分片 */
    private static final class PendingBlock {

        private boolean toolUse;
        private String id;
        private String name;
        private final StringBuilder arguments = new StringBuilder();

        boolean isToolUse() {
            return toolUse;
        }

        ToolCall toCall() {
            String callId = StrUtil.isBlank(id) ? UUID.randomUUID().toString() : id;
            return new ToolCall(callId, name, arguments.toString());
        }
    }
}
