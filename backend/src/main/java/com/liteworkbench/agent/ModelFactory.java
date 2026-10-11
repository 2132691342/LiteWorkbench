package com.liteworkbench.agent;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.ArrayUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.crypto.symmetric.SymmetricCrypto;
import com.liteworkbench.agent.spi.ProviderStore;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.SecurityProperties;
import com.liteworkbench.domain.constant.RedisKeyConst;
import com.liteworkbench.domain.entity.WbProviderDO;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.extensions.model.anthropic.AnthropicChatModel;
import io.agentscope.extensions.model.ollama.OllamaChatModel;
import io.agentscope.extensions.model.openai.OpenAIChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 按平台级供应方配置构建并缓存模型实例，覆盖 OpenAI / Anthropic / Ollama 三条通用协议 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelFactory {

    /** 供应方配置缓存 TTL */
    private static final Duration PROVIDER_CACHE_TTL = Duration.ofMinutes(5);
    /** 模型实例缓存容量 */
    private static final int MODEL_CACHE_CAPACITY = 64;
    /** 新格式加密算法 */
    private static final String GCM_TRANSFORMATION = "AES/GCM/NoPadding";
    /** GCM IV 长度，字节 */
    private static final int GCM_IV_LENGTH = 12;
    /** GCM 认证标签长度，位 */
    private static final int GCM_TAG_BITS = 128;
    /** 旧格式加密算法，仅用于解密兼容 */
    private static final String LEGACY_TRANSFORMATION = "AES/ECB/PKCS5Padding";

    private final ProviderStore providerStore;
    private final AgentProperties agentProperties;
    private final SecurityProperties securityProperties;
    private final StringRedisTemplate redisTemplate;

    /** provider|model -> Model 实例，容量 64 的最近最少使用缓存 */
    private final Map<String, Model> modelCache = Collections.synchronizedMap(
            new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Model> eldest) {
                    return size() > MODEL_CACHE_CAPACITY;
                }
            });

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

    /** 清除某供应方的全部缓存 */
    public void evictProvider(String providerCode) {
        String prefix = providerCode + ".";
        modelCache.keySet().stream().filter(key -> key.startsWith(prefix)).toList()
                .forEach(modelCache::remove);
    }

    /** 供应方配置：Redis 5 分钟缓存，未命中回源 */
    public WbProviderDO loadProvider(String providerCode) {
        String cacheKey = StrUtil.format(RedisKeyConst.CACHE_PROVIDER, providerCode);
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

    /** 默认供应方：会话未指定时使用，无可用时给出明确配置指引 */
    public WbProviderDO resolveDefaultProvider() {
        WbProviderDO provider = providerStore.findDefault();
        if (Objects.isNull(provider)) {
            throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED,
                    "未找到可用模型供应方，请前往「设置-供应方」配置并启用");
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

    /** 按协议构建模型实例：openai / anthropic 必须配置密钥，ollama 免密钥 */
    private Model buildModel(WbProviderDO provider, String modelName) {
        return switch (resolveProtocol(provider)) {
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

    /** 协议判定：编码命中 anthropic/ollama 关键字即用对应协议，其余一律按 OpenAI 兼容端点处理 */
    private String resolveProtocol(WbProviderDO provider) {
        String code = StrUtil.nullToEmpty(provider.getProviderCode()).toLowerCase();
        if (code.contains("anthropic") || code.contains("claude")) {
            return "anthropic";
        }
        if (code.contains("ollama")) {
            return "ollama";
        }
        return "openai";
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

    /** 密钥加密：AES/GCM，随机 IV 前置后 Base64 */
    public String encrypt(String plain) {
        try {
            byte[] iv = RandomUtil.randomBytes(GCM_IV_LENGTH);
            Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey(), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] body = cipher.doFinal(StrUtil.bytes(plain, StandardCharsets.UTF_8));
            return Base64.encode(ArrayUtil.addAll(iv, body));
        } catch (GeneralSecurityException e) {
            throw new BizException(ErrorCodeEnum.INTERNAL_ERROR, "密钥加密失败");
        }
    }

    /** 密钥解密：优先 GCM，失败回退旧 ECB 格式 */
    public String decrypt(String encrypted) {
        if (StrUtil.isBlank(encrypted)) {
            throw new BizException(ErrorCodeEnum.PROVIDER_NOT_CONFIGURED);
        }
        try {
            byte[] raw = Base64.decode(encrypted);
            if (raw.length > GCM_IV_LENGTH) {
                Cipher cipher = Cipher.getInstance(GCM_TRANSFORMATION);
                cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey(), "AES"),
                        new GCMParameterSpec(GCM_TAG_BITS, Arrays.copyOf(raw, GCM_IV_LENGTH)));
                byte[] body = cipher.doFinal(Arrays.copyOfRange(raw, GCM_IV_LENGTH, raw.length));
                return StrUtil.str(body, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            log.warn("[provider.decrypt] GCM 解密失败，回退旧格式 {}", e.getMessage());
        }
        return new SymmetricCrypto(LEGACY_TRANSFORMATION, aesKey()).decryptStr(encrypted);
    }

    /** 加密密钥：安全因子摘要前 16 字节 */
    private byte[] aesKey() {
        return Arrays.copyOf(DigestUtil.sha256(securityProperties.getSecret()), 16);
    }
}
