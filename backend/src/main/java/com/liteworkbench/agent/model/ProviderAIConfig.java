package com.liteworkbench.agent.model;

import cn.hutool.ai.core.AIConfig;

import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;

/** hutool-ai 配置契约实现：把平台级供应方配置适配为 AIConfig，内核与 hutool-ai 服务共用这一份配置 */
public class ProviderAIConfig implements AIConfig {

    /** 供应方编码，hutool-ai 依此选择服务实现 */
    private final String modelName;
    private String apiKey;
    private String apiUrl;
    private String model;
    private int timeout = 30_000;
    private int readTimeout = 180_000;
    private boolean hasProxy;
    private Proxy proxy;
    private final Map<String, Object> additional = new LinkedHashMap<>();

    public ProviderAIConfig(String modelName) {
        this.modelName = modelName;
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    @Override
    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    @Override
    public String getApiKey() {
        return apiKey;
    }

    @Override
    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }

    @Override
    public String getApiUrl() {
        return apiUrl;
    }

    @Override
    public void setModel(String model) {
        this.model = model;
    }

    @Override
    public String getModel() {
        return model;
    }

    @Override
    public void putAdditionalConfigByKey(String key, Object value) {
        additional.put(key, value);
    }

    @Override
    public Object getAdditionalConfigByKey(String key) {
        return additional.get(key);
    }

    @Override
    public Map<String, Object> getAdditionalConfigMap() {
        return additional;
    }

    @Override
    public void setTimeout(int timeout) {
        this.timeout = timeout;
    }

    @Override
    public int getTimeout() {
        return timeout;
    }

    @Override
    public void setReadTimeout(int readTimeout) {
        this.readTimeout = readTimeout;
    }

    @Override
    public int getReadTimeout() {
        return readTimeout;
    }

    @Override
    public boolean getHasProxy() {
        return hasProxy;
    }

    @Override
    public void setHasProxy(boolean hasProxy) {
        this.hasProxy = hasProxy;
    }

    @Override
    public Proxy getProxy() {
        return proxy;
    }

    @Override
    public void setProxy(Proxy proxy) {
        this.proxy = proxy;
    }
}
