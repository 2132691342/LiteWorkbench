package com.liteworkbench.service;

import cn.hutool.core.date.LocalDateTimeUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.ProviderREQ;
import com.liteworkbench.domain.entity.WbProviderDO;
import com.liteworkbench.domain.vo.ProviderVO;
import com.liteworkbench.mapper.WbProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;

/** 模型供应方：平台级配置 CRUD、连通性测试、密钥加解密与缓存失效 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProviderService {

    /** 连通性测试超时 */
    private static final Duration TEST_TIMEOUT = Duration.ofSeconds(15L);

    private final WbProviderMapper providerMapper;
    private final ModelFactory modelFactory;

    /** 供应方列表，密钥脱敏 */
    public List<ProviderVO> list() {
        return providerMapper.selectAll().stream().map(this::toVO).toList();
    }

    /** 新建供应方 */
    @Transactional(rollbackFor = Exception.class)
    public String create(ProviderREQ req, String userId) {
        var conflict = providerMapper.countByCodeExclude(req.getProviderCode(), "");
        if (conflict > 0) {
            throw new BizException(ErrorCodeEnum.STATE_CONFLICT, "供应方编码已存在");
        }
        WbProviderDO provider = new WbProviderDO();
        provider.setProviderId(IdGenerator.generate(IdPrefixConst.PROVIDER));
        provider.setProviderCode(req.getProviderCode());
        provider.setProviderName(req.getProviderName());
        provider.setBaseUrl(req.getBaseUrl());
        provider.setApiKeyEnc(encryptIfPresent(req.getApiKey()));
        provider.setDefaultModel(req.getDefaultModel());
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

    /** 删除供应方 */
    public void delete(String providerId, String userId) {
        WbProviderDO provider = require(providerId);
        providerMapper.deleteById(providerId, userId);
        modelFactory.evictProvider(provider.getProviderCode());
    }

    /** 连通性测试：向构建好的模型发一条极短消息，取首帧即返回 */
    public ProviderVO test(String providerId) {
        WbProviderDO provider = require(providerId);
        long start = System.currentTimeMillis();
        try {
            io.agentscope.core.model.Model model = modelFactory.get(provider.getProviderCode(),
                    StrUtil.blankToDefault(provider.getDefaultModel(), "gpt-4o-mini"));
            model.stream(List.of(new io.agentscope.core.message.UserMessage("ping")),
                    List.of(), null)
                    .take(1)
                    .blockLast(TEST_TIMEOUT);
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
        vo.setUpdatedAt(toMillis(provider.getUpdatedAt()));
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

    private Long toMillis(LocalDateTime time) {
        return Objects.isNull(time) ? null : LocalDateTimeUtil.toEpochMilli(time);
    }
}
