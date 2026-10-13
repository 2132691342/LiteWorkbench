package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.spi.ProviderStore;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.mapper.WbProviderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/** 供应方配置读取的 service 侧实现，供 agent 装配模型时读取平台级配置 */
@Component
@RequiredArgsConstructor
public class AgentProviderStore implements ProviderStore {

    private final WbProviderMapper providerMapper;

    @Override
    public WbProviderDO findByCode(String providerCode) {
        return providerMapper.selectByCode(providerCode);
    }

    @Override
    public WbProviderDO findById(String providerId) {
        return providerMapper.selectById(providerId);
    }

    /** 默认可用供应方：配置完整的云端供应方（OpenAI 兼容族）优先，其次 ollama，再其次任一启用项 */
    @Override
    public WbProviderDO findDefault() {
        List<WbProviderDO> enabled = providerMapper.selectAll().stream()
                .filter(provider -> Boolean.TRUE.equals(provider.getEnabled()))
                .toList();
        return enabled.stream().filter(this::isReadyCloud).findFirst()
                .orElseGet(() -> enabled.stream().filter(provider -> isProtocol(provider, "ollama"))
                        .findFirst().orElse(enabled.isEmpty() ? null : enabled.get(0)));
    }

    /** 云端供应方：ollama 之外均按 OpenAI 兼容族处理，要求密钥与端点齐备 */
    private boolean isReadyCloud(WbProviderDO provider) {
        return !isProtocol(provider, "ollama")
                && StrUtil.isNotBlank(provider.getBaseUrl())
                && StrUtil.isNotBlank(provider.getApiKeyEnc());
    }

    /** 协议判定：编码含协议关键字即命中，未知编码按 OpenAI 兼容处理 */
    private boolean isProtocol(WbProviderDO provider, String protocol) {
        return provider.getProviderCode().toLowerCase().contains(protocol);
    }
}
