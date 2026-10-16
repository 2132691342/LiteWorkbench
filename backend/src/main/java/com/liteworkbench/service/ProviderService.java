package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelEventListener;
import com.liteworkbench.agent.model.ModelTypes;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.ProviderPresetEnum;
import com.liteworkbench.domain.dto.ProviderREQ;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.domain.vo.ProviderPresetVO;
import com.liteworkbench.domain.vo.ProviderVO;
import com.liteworkbench.mapper.WbProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 模型供应方：平台级配置 CRUD、连通性测试、密钥加解密与缓存失效 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    /** 连通性测试超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15L);
    /** 连通性测试最大输出 token，够触发一次完整调用即可 */
    private static final int TEST_MAX_TOKENS = 16;

    private final WbProviderMapper providerMapper;
    private final ModelFactory modelFactory;

    /** 供应方列表，密钥脱敏 */
    public List<ProviderVO> list() {
        return providerMapper.selectAll().stream().map(this::toVO).toList();
    }

    /** 预设列表：与 hutool-ai 对齐的四个预设，前端新建表单下拉用 */
    public List<ProviderPresetVO> presets() {
        return Arrays.stream(ProviderPresetEnum.values()).map(preset -> {
            ProviderPresetVO vo = new ProviderPresetVO();
            vo.setProviderCode(preset.getCode());
            vo.setBaseUrl(preset.getBaseUrl());
            vo.setDefaultModel(preset.getDefaultModel());
            vo.setProtocol("openai");
            vo.setRequiresKey(true);
            return vo;
        }).toList();
    }

    /** 新建供应方：端点与默认模型留空时回落预设默认值 */
    @Transactional(rollbackFor = Exception.class)
    public String create(ProviderREQ req, String userId) {
        // 协议集合固定为四家 OpenAI 兼容预设，未知编码（如本地 ollama 端点）没有对应协议通道，直接拒绝
        requirePresetCode(req.getProviderCode());
        var conflict = providerMapper.countByCodeExclude(req.getProviderCode(), "");
        if (conflict > 0) {
            throw new BizException(ErrorCodeEnum.STATE_CONFLICT, "供应方编码已存在");
        }
        ProviderPresetEnum preset = ProviderPresetEnum.ofCode(req.getProviderCode());
        WbProviderDO provider = new WbProviderDO();
        provider.setProviderId(IdGenerator.generate(IdPrefixConst.PROVIDER));
        provider.setProviderCode(req.getProviderCode());
        provider.setProviderName(req.getProviderName());
        provider.setBaseUrl(StrUtil.blankToDefault(req.getBaseUrl(),
                Objects.nonNull(preset) ? preset.getBaseUrl() : null));
        provider.setApiKeyEnc(encryptIfPresent(req.getApiKey()));
        provider.setDefaultModel(StrUtil.blankToDefault(req.getDefaultModel(),
                Objects.nonNull(preset) ? preset.getDefaultModel() : null));
        provider.setEnabled(Objects.nonNull(req.getEnabled()) ? req.getEnabled() : Boolean.TRUE);
        provider.setCreatedBy(userId);
        providerMapper.insert(provider);
        modelFactory.evictProvider(req.getProviderCode());
        log.info("[provider.create] providerId={} code={}", provider.getProviderId(), provider.getProviderCode());
        return provider.getProviderId();
    }

    /** 更新供应方，密钥传脱敏值视为不修改 */
    public void update(ProviderREQ req, String userId) {
        if (StrUtil.isBlank(req.getProviderId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "providerId 不能为空");
        }
        requirePresetCode(req.getProviderCode());
        WbProviderDO exists = require(req.getProviderId());
        var conflict = providerMapper.countByCodeExclude(req.getProviderCode(), req.getProviderId());
        if (conflict > 0) {
            throw new BizException(ErrorCodeEnum.STATE_CONFLICT, "供应方编码已存在");
        }
        WbProviderDO provider = new WbProviderDO();
        provider.setProviderId(req.getProviderId());
        provider.setProviderName(req.getProviderName());
        provider.setBaseUrl(req.getBaseUrl());
        provider.setApiKeyEnc(encryptIfPresent(req.getApiKey()));
        provider.setDefaultModel(req.getDefaultModel());
        provider.setEnabled(req.getEnabled());
        provider.setUpdatedBy(userId);
        providerMapper.update(provider);
        modelFactory.evictProvider(exists.getProviderCode());
    }

    public void delete(String providerId, String userId) {
        WbProviderDO provider = require(providerId);
        providerMapper.deleteById(providerId, userId);
        modelFactory.evictProvider(provider.getProviderCode());
    }

    /** 连通性测试：向构建好的模型发一条极短消息，拿到任意响应即视为连通 */
    public ProviderVO test(String providerId) {
        WbProviderDO provider = require(providerId);
        long start = System.currentTimeMillis();
        try {
            ModelClient client = modelFactory.client(provider.getProviderCode(),
                    StrUtil.blankToDefault(provider.getDefaultModel(), "gpt-4o-mini"));
            ModelTypes.Request request = new ModelTypes.Request();
            request.setMessages(List.of(ModelTypes.Msg.user("ping")));
            request.setMaxTokens(TEST_MAX_TOKENS);
            request.setTimeoutSeconds((int) TEST_TIMEOUT.getSeconds());
            client.stream(request, new ModelEventListener() {
            }, () -> false);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("[provider.test] providerId={} 失败 {}", providerId, e.getMessage());
            throw new BizException(ErrorCodeEnum.MODEL_CALL_FAILED, e.getMessage());
        }
        long cost = System.currentTimeMillis() - start;
        log.info("[provider.test] providerId={} cost={}ms", providerId, cost);
        return toVO(provider);
    }

    private WbProviderDO require(String providerId) {
        WbProviderDO provider = providerMapper.selectById(providerId);
        if (Objects.isNull(provider)) {
            throw new BizException(ErrorCodeEnum.RESOURCE_NOT_FOUND, providerId);
        }
        return provider;
    }

    /** 协议码白名单：只接受四家预设，存量自定义行可读可删，但不允许新建或改写成未知协议 */
    private void requirePresetCode(String code) {
        if (Objects.isNull(ProviderPresetEnum.ofCode(code))) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的供应方协议，仅支持：openai / deepseek / doubao / grok");
        }
    }

    private String encryptIfPresent(String apiKey) {
        if (StrUtil.isBlank(apiKey) || apiKey.contains("***")) {
            return null;
        }
        return modelFactory.encrypt(apiKey);
    }

    private ProviderVO toVO(WbProviderDO provider) {
        ProviderVO vo = new ProviderVO();
        vo.setProviderId(provider.getProviderId());
        vo.setProviderCode(provider.getProviderCode());
        vo.setProviderName(provider.getProviderName());
        vo.setBaseUrl(provider.getBaseUrl());
        vo.setApiKeyMasked(mask(provider.getApiKeyEnc()));
        vo.setHasApiKey(StrUtil.isNotBlank(provider.getApiKeyEnc()));
        vo.setDefaultModel(provider.getDefaultModel());
        vo.setEnabled(provider.getEnabled());
        vo.setUpdatedAt(TimeUtil.toMillis(provider.getUpdatedAt()));
        return vo;
    }

    /** 脱敏：保留前 3 后 4，原文不足 8 位只保留前 3 */
    private String mask(String encrypted) {
        if (StrUtil.isBlank(encrypted)) {
            return null;
        }
        try {
            String plain = modelFactory.decrypt(encrypted);
            if (plain.length() <= 8) {
                return plain.substring(0, Math.min(3, plain.length())) + "***";
            }
            return plain.substring(0, 3) + "***" + plain.substring(plain.length() - 4);
        } catch (Exception e) {
            return "***";
        }
    }
}
