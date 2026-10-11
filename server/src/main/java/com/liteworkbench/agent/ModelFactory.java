package com.liteworkbench.agent;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.symmetric.SymmetricCrypto;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.SecurityProperties;
import com.liteworkbench.agent.spi.ProviderStore;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.entity.WbProviderDO;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** 按平台级供应方配置构建并缓存模型实例，覆盖 OpenAI / Anthropic / Ollama 三条通用协议 */
@Slf4j
@Component
public class ModelFactory {

    /** 供应方配置缓存 TTL */
    private static final Duration PROVIDER_CACHE_TTL = Duration.ofMinutes(5);

    private final ProviderStore providerStore;
    private final AgentProperties agentProperties;
    private final SecurityProperties securityProperties;
    private final StringRedisTemplate redisTemplate;

    /** provider|model -> Model 实例 */
    private final Map<String, Model> modelCache = new ConcurrentHashMap<>();

    public ModelFactory(ProviderStore providerStore, AgentProperties agentProperties,
                        SecurityProperties securityProperties, StringRedisTemplate redisTemplate) {
        this.providerStore = providerStore;
        this.agentProperties = agentProperties;
        this.securityProperties = securityProperties;
        this.redisTemplate = redisTemplate;
    }

    /** 取模型实例，带二级缓存：Redis 存配置快照，本地存构建好的 Model */
    public Model get(String providerCode, String modelName) {
        String cacheKey = StrUtil.format("{}.{}", providerCode, modelName);
        Model cached = modelCache.get(cacheKey);
        if (Objects.nonNull(cached)) {
            return cached;
        }
        WbProviderDO provider = loadProvider(providerCode);
        Model model = buildModel(provider, modelName);
        modelCache.put(cacheKey, model);
        return model;
    }

    /** 清除指定组合的缓存 */
    public void evict(String providerCode, String modelName) {
        if (StrUtil.isBlank(modelName)) {
            modelCache.keySet().stream()
                    .filter(key -> key.startsWith(cachePrefix(providerCode)))
                    .forEach(modelCache::remove);
            return;
        }
        modelCache.remove(StrUtil.format("{}.{}", providerCode, modelName));
    }

    /** 清除某供应方的全部缓存 */
    public void evictProvider(String providerCode) {
        String prefix = cachePrefix(providerCode);
        modelCache.keySet().stream().filter(key -> key.startsWith(prefix)).forEach(modelCache::remove);
    }

    /** 供应方配置：Redis 5 分钟缓存，未命中回源 */
    public WbProviderDO loadProvider(String providerCode) {
        String cacheKey = RedisKeyConst.cacheProvider(providerCode);
        String cachedId = redisTemplate.opsForValue().get(cacheKey);
        WbProviderDO provider;
        if (StrUtil.isNotBlank(cachedId)) {
            provider = providerStore.findById(cachedId);
            if (Objects.isNull(provider)) {
                redisTemplate.delete(cacheKey);
            }
        } else {
            provider = providerStore.findByCode(providerCode);
            if (Objects.nonNull(provider)) {
                redisTemplate.opsForValue().set(cacheKey, provider.getProviderId(), PROVIDER_CACHE_TTL);
            }
        }
        if (Objects.isNull(provider) || !Boolean.TRUE.equals(provider.getEnabled())) {
            throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED, providerCode);
        }
        return provider;
    }

    /** 模型名三级兜底：会话指定 > 供应方默认 > 全局默认 */
    public String resolveModelName(WbProviderDO provider, String sessionModel) {
        if (StrUtil.isNotBlank(sessionModel)) {
            return sessionModel;
        }
        if (StrUtil.isNotBlank(provider.getDefaultModel())) {
            return provider.getDefaultModel();
        }
        return agentProperties.getModel().getDefaultName();
    }

    /** 按编码构建模型实例：openai / anthropic 必须配置密钥，ollama 免密钥 */
    private Model buildModel(WbProviderDO provider, String modelName) {
        return switch (provider.getProviderCode()) {
            case "openai" -> OpenAIChatModel.builder()
                    .apiKey(requireApiKey(provider))
                    .modelName(modelName)
                    .stream(agentProperties.getModel().isStream())
                    .baseUrl(provider.getBaseUrl())
                    .generateOptions(generateOptions())
                    .build();
            case "anthropic" -> AnthropicChatModel.builder()
                    .apiKey(requireApiKey(provider))
                    .modelName(modelName)
                    .stream(agentProperties.getModel().isStream())
                    .baseUrl(provider.getBaseUrl())
                    .defaultOptions(generateOptions())
                    .build();
            case "ollama" -> OllamaChatModel.builder()
                    .modelName(modelName)
                    .stream(agentProperties.getModel().isStream())
                    .baseUrl(provider.getBaseUrl())
                    .build();
            default -> throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED, provider.getProviderCode());
        };
    }

    /** openai / anthropic 必须有密钥 */
    private String requireApiKey(WbProviderDO provider) {
        if (StrUtil.isBlank(provider.getApiKeyEnc())) {
            throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED, provider.getProviderCode());
        }
        return decrypt(provider.getApiKeyEnc());
    }

    private GenerateOptions generateOptions() {
        return GenerateOptions.builder()
                .temperature(agentProperties.getModel().getTemperature())
                .build();
    }

    /** 密钥解密：AES/ECB/PKCS5，密钥取安全因子摘要前 16 字节 */
    public String decrypt(String encrypted) {
        if (StrUtil.isBlank(encrypted)) {
            throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED);
        }
        return aes().decryptStr(encrypted);
    }

    /** 密钥加密，落库用 */
    public String encrypt(String plain) {
        return aes().encryptHex(plain);
    }

    private SymmetricCrypto aes() {
        byte[] key = DigestUtil.sha256(securityProperties.getSecret());
        byte[] trimmed = new byte[16];
        System.arraycopy(key, 0, trimmed, 0, 16);
        return new SymmetricCrypto("AES/ECB/PKCS5Padding", trimmed);
    }

    private String cachePrefix(String providerCode) {
        return providerCode + ".";
    }
}
