package com.liteworkbench.agent.spi;

import com.liteworkbench.domain.entity.WbProviderDO;

/** 供应方配置读取端口：agent 只认此接口，由 service 侧适配实现 */
public interface ProviderStore {

    /** 按编码查供应方 */
    WbProviderDO findByCode(String providerCode);

    /** 按主键查供应方 */
    WbProviderDO findById(String providerId);
}
