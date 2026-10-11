package com.liteworkbench.service;

import com.liteworkbench.agent.spi.ProviderStore;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.mapper.WbProviderMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

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
}
