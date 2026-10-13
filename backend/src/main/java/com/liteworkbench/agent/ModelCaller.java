package com.liteworkbench.agent;

import cn.hutool.ai.AIUtil;
import cn.hutool.ai.core.Message;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelTypes;
import com.liteworkbench.agent.model.ProviderAIConfig;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.entity.WbProviderDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 模型同步调用：创作台 AI 辅助与供应方连通性测试共用，不建 Run、不落事件；优先复用 hutool-ai 原生服务 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelCaller {

    private final ModelFactory modelFactory;
    private final AgentProperties agentProperties;

    /** 用默认供应方同步生成文本 */
    public String complete(String prompt) {
        WbProviderDO provider = modelFactory.resolveDefaultProvider();
        return complete(prompt, provider.getProviderCode(), modelFactory.resolveModelName(provider, null));
    }

    /** 静默调用：失败或空文本返回 null，供会话自动命名等不影响主链路的场景使用 */
    public String completeQuietly(String prompt) {
        try {
            String text = complete(prompt);
            return StrUtil.isBlank(text) ? null : text;
        } catch (Exception e) {
            log.warn("[model.sync] 静默调用失败 {}", e.getMessage());
            return null;
        }
    }

    /** 指定供应方与模型同步生成文本，超时与失败转对应错误码 */
    public String complete(String prompt, String providerCode, String modelName) {
        WbProviderDO provider = modelFactory.loadProvider(providerCode);
        ProviderAIConfig config = modelFactory.aiConfig(provider, modelName);
        // hutool-ai 覆盖的协议优先走其原生服务，失败或返回空文本再回退自研客户端
        if (modelFactory.hutoolAiSupported(provider.getProviderCode())) {
            String text = hutoolAiComplete(config, prompt, providerCode);
            if (StrUtil.isNotBlank(text)) {
                return text;
            }
        }
        return kernelComplete(prompt, providerCode, modelName);
    }

    /** 走 hutool-ai 的同步对话，任何异常都降级为 null 交由内核客户端兜底 */
    private String hutoolAiComplete(ProviderAIConfig config, String prompt, String providerCode) {
        try {
            String raw = AIUtil.chat(config, List.of(new Message(ModelTypes.Role.USER, prompt)));
            String text = extractText(raw);
            if (StrUtil.isBlank(text)) {
                log.warn("[model.sync] hutool-ai 返回空文本 provider={}", providerCode);
            }
            return text;
        } catch (Exception e) {
            log.warn("[model.sync] hutool-ai 调用失败，回退自研客户端 provider={} {}", providerCode, e.getMessage());
            return null;
        }
    }

    /** 走自研模型的流式通道并汇总为完整文本 */
    private String kernelComplete(String prompt, String providerCode, String modelName) {
        ModelClient client = modelFactory.client(providerCode, modelName);
        ModelTypes.Request request = new ModelTypes.Request();
        request.setMessages(List.of(ModelTypes.Msg.user(prompt)));
        request.setTemperature(agentProperties.getModel().getTemperature());
        request.setTimeoutSeconds(agentProperties.getModel().getSyncTimeoutSeconds());
        ModelTypes.StreamResult result;
        try {
            result = client.stream(request, new com.liteworkbench.agent.model.ModelEventListener() {
            }, () -> false);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[model.sync] 调用失败 provider={} {}", providerCode, e.getMessage());
            throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED, e.getMessage());
        }
        if (StrUtil.isBlank(result.getText())) {
            throw new BizException(ErrorCodeEnum.MODEL_OUTPUT_INVALID);
        }
        return result.getText();
    }

    /** 从三家供应商的原始响应里取正文，取不到返回空串 */
    private String extractText(String raw) {
        if (StrUtil.isBlank(raw)) {
            return StrUtil.EMPTY;
        }
        try {
            JSONObject body = JSONUtil.parseObj(raw);
            JSONArray choices = body.getJSONArray("choices");
            if (Objects.nonNull(choices) && !choices.isEmpty()) {
                JSONObject message = choices.getJSONObject(0).getJSONObject("message");
                if (Objects.nonNull(message) && StrUtil.isNotBlank(message.getStr("content"))) {
                    return message.getStr("content");
                }
            }
            JSONObject message = body.getJSONObject("message");
            if (Objects.nonNull(message) && StrUtil.isNotBlank(message.getStr("content"))) {
                return message.getStr("content");
            }
            for (String key : List.of("content", "result", "text", "answer")) {
                if (StrUtil.isNotBlank(body.getStr(key))) {
                    return body.getStr(key);
                }
            }
        } catch (Exception e) {
            log.warn("[model.sync] 响应解析失败 {}", e.getMessage());
        }
        return StrUtil.EMPTY;
    }
}
