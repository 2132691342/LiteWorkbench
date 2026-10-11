package com.liteworkbench.agent;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.AgentConst;
import com.liteworkbench.domain.entity.WbProviderDO;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.message.ContentBlock;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.UserMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** 模型同步调用：辅助写作与工作流 llm 节点共用，不建 Run、不落事件 */
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

    /** 指定供应方与模型同步生成文本，超时与失败转对应错误码 */
    public String complete(String prompt, String providerCode, String modelName) {
        Model model = modelFactory.get(providerCode, modelName);
        GenerateOptions options = GenerateOptions.builder()
                .temperature(agentProperties.getModel().getTemperature())
                .build();
        List<ChatResponse> responses;
        try {
            responses = model.stream(List.<Msg>of(new UserMessage(prompt)), null, options)
                    .collectList()
                    .block(Duration.ofSeconds(AgentConst.SYNC_CALL_TIMEOUT_SECONDS));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("[model.sync] 调用失败 provider={} {}", providerCode, e.getMessage());
            throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED, e.getMessage());
        }
        if (Objects.isNull(responses)) {
            throw new BizException(ErrorCodeEnum.MODEL_CALL_TIMEOUT);
        }
        StringBuilder text = new StringBuilder();
        for (ChatResponse response : responses) {
            if (Objects.isNull(response) || Objects.isNull(response.getContent())) {
                continue;
            }
            for (ContentBlock block : response.getContent()) {
                if (block instanceof TextBlock textBlock && StrUtil.isNotBlank(textBlock.getText())) {
                    text.append(textBlock.getText());
                }
            }
        }
        if (text.length() == 0) {
            throw new BizException(ErrorCodeEnum.MODEL_OUTPUT_INVALID);
        }
        return text.toString();
    }
}
