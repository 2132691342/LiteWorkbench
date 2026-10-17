package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.spi.MemoryPort;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.constant.MemoryConst;
import com.liteworkbench.domain.dto.MemoryREQ;
import com.liteworkbench.domain.entity.WbMemoryDO;
import com.liteworkbench.domain.vo.MemoryVO;
import com.liteworkbench.mapper.WbMemoryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 长期记忆：容量闸门、同 key 覆盖、注入拼装，全部按用户隔离 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemoryService implements MemoryPort {

    /** 默认维度 */
    private static final String DEFAULT_CATEGORY = "fact";

    private final WbMemoryMapper memoryMapper;

    /** 分页列出当前用户的长期记忆 */
    public PageResult<MemoryVO> page(MemoryREQ req) {
        String userId = LoginContext.userId();
        long total = memoryMapper.countPage(userId, req, req.getCategory());
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<MemoryVO> list = memoryMapper.selectPage(userId, req, req.getCategory(),
                req.offset(), req.pageSize()).stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 页面手工新增 */
    public String create(MemoryREQ req) {
        String userId = LoginContext.userId();
        return write(userId, req.getMemoryKey(), req.getMemoryValue(), req.getCategory(), req.getWeight());
    }

    /** 页面手工修改：只改内容与维度，键不允许改（改键等于换一条记忆） */
    public void update(MemoryREQ req) {
        String userId = LoginContext.userId();
        WbMemoryDO exist = memoryMapper.selectById(userId, req.getMemoryId());
        if (Objects.isNull(exist)) {
            throw new BizException(ErrorCodeEnum.MEMORY_NOT_FOUND, req.getMemoryId());
        }
        WbMemoryDO update = new WbMemoryDO()
                .setUserId(userId)
                .setMemoryKey(exist.getMemoryKey())
                .setMemoryValue(requireValue(req.getMemoryValue()))
                .setCategory(normalizeCategory(req.getCategory()))
                .setWeight(normalizeWeight(req.getWeight()))
                .setUpdatedBy(userId);
        memoryMapper.upsertByKey(update);
        log.info("[memory.update] userId={} memoryId={}", userId, req.getMemoryId());
    }

    /** 删除：软删，保留审计痕迹 */
    public void remove(String memoryId) {
        String userId = LoginContext.userId();
        int rows = memoryMapper.deleteById(userId, memoryId, userId);
        if (rows <= 0) {
            throw new BizException(ErrorCodeEnum.MEMORY_NOT_FOUND, memoryId);
        }
        log.info("[memory.delete] userId={} memoryId={}", userId, memoryId);
    }

    @Override
    /** 写入一条记忆：同 key 覆盖，超容量按权重淘汰 */
    public String write(String userId, String key, String value, String category, Integer weight) {
        if (StrUtil.isBlank(userId)) {
            throw new BizException(ErrorCodeEnum.MEMORY_EMPTY, "记忆归属用户缺失");
        }
        String memoryKey = StrUtil.maxLength(StrUtil.trimToEmpty(key), MemoryConst.MAX_KEY_LENGTH);
        if (StrUtil.isBlank(memoryKey)) {
            throw new BizException(ErrorCodeEnum.MEMORY_EMPTY, "记忆键不能为空");
        }
        WbMemoryDO memory = new WbMemoryDO()
                .setMemoryId(IdGenerator.generate(IdPrefixConst.MEMORY))
                .setUserId(userId)
                .setMemoryKey(memoryKey)
                .setMemoryValue(requireValue(value))
                .setCategory(normalizeCategory(category))
                .setWeight(normalizeWeight(weight))
                .setHitCount(0)
                .setCreatedBy(userId)
                .setUpdatedBy(userId);
        enforceCapacity(userId);
        try {
            if (memoryMapper.upsertByKey(memory) <= 0) {
                memoryMapper.insert(memory);
            }
        } catch (DuplicateKeyException e) {
            // 并发写同 key：唯一索引兜底，退化为覆盖更新
            memoryMapper.upsertByKey(memory);
        }
        log.info("[memory.write] userId={} key={} category={}", userId, memoryKey, memory.getCategory());
        return StrUtil.format("已记住「{}」：{}", memoryKey, memory.getMemoryValue());
    }

    @Override
    /** 按关键字检索记忆，返回可直接注入提示词的文本块 */
    public String search(String userId, String keyword, int limit) {
        if (StrUtil.isBlank(userId)) {
            return "（无可检索的记忆）";
        }
        int size = limit <= 0 ? MemoryConst.SEARCH_LIMIT : Math.min(limit, MemoryConst.SEARCH_LIMIT);
        List<WbMemoryDO> memories = memoryMapper.search(userId, StrUtil.trimToEmpty(keyword), size);
        if (memories.isEmpty()) {
            return StrUtil.format("（没有与「{}」相关的记忆）", keyword);
        }
        StringBuilder text = new StringBuilder();
        for (WbMemoryDO memory : memories) {
            memoryMapper.incrementHit(memory.getMemoryId());
            appendLine(text, memory);
        }
        return StrUtil.maxLength(text.toString().trim(), MemoryConst.TOOL_OUTPUT_MAX);
    }

    @Override
    /** 取权重最高的若干条记忆，拼成系统提示末尾的注入块 */
    public String promptBlock(String userId) {
        if (StrUtil.isBlank(userId)) {
            return StrUtil.EMPTY;
        }
        List<WbMemoryDO> memories = memoryMapper.selectTop(userId, MemoryConst.PROMPT_LIMIT);
        if (memories.isEmpty()) {
            return StrUtil.EMPTY;
        }
        StringBuilder text = new StringBuilder();
        for (WbMemoryDO memory : memories) {
            appendLine(text, memory);
        }
        return StrUtil.maxLength(text.toString().trim(), MemoryConst.PROMPT_MAX_CHARS);
    }

    /** 容量闸门：超限先淘汰最低权重一行，保证写入永远能成功而不是报错 */
    private void enforceCapacity(String userId) {
        for (int i = 0; i < MemoryConst.PROMPT_LIMIT; i++) {
            if (memoryMapper.countByUser(userId) < MemoryConst.MAX_PER_USER) {
                return;
            }
            memoryMapper.evictLowest(userId);
        }
        long remaining = memoryMapper.countByUser(userId);
        if (remaining >= MemoryConst.MAX_PER_USER) {
            throw new BizException(ErrorCodeEnum.MEMORY_LIMIT_REACHED,
                    StrUtil.format("记忆条数已达上限 {}", MemoryConst.MAX_PER_USER));
        }
    }

    private void appendLine(StringBuilder text, WbMemoryDO memory) {
        text.append("- [").append(memory.getCategory()).append("] ")
                .append(memory.getMemoryKey()).append("：")
                .append(StrUtil.maxLength(StrUtil.nullToEmpty(memory.getMemoryValue()),
                        MemoryConst.MAX_VALUE_LENGTH))
                .append('\n');
    }

    private String requireValue(String value) {
        String trimmed = StrUtil.trimToEmpty(value);
        if (StrUtil.isBlank(trimmed)) {
            throw new BizException(ErrorCodeEnum.MEMORY_EMPTY, "记忆内容不能为空");
        }
        return StrUtil.maxLength(trimmed, MemoryConst.MAX_VALUE_LENGTH);
    }

    private String normalizeCategory(String category) {
        String candidate = StrUtil.blankToDefault(category, DEFAULT_CATEGORY);
        return MemoryConst.CATEGORIES.contains(candidate) ? candidate : DEFAULT_CATEGORY;
    }

    private int normalizeWeight(Integer weight) {
        if (Objects.isNull(weight)) {
            return 0;
        }
        return Math.max(0, Math.min(weight, 9));
    }

    private MemoryVO toVO(WbMemoryDO memory) {
        MemoryVO vo = new MemoryVO();
        vo.setMemoryId(memory.getMemoryId());
        vo.setMemoryKey(memory.getMemoryKey());
        vo.setMemoryValue(memory.getMemoryValue());
        vo.setCategory(memory.getCategory());
        vo.setWeight(memory.getWeight());
        vo.setHitCount(memory.getHitCount());
        vo.setUpdatedAt(TimeUtil.toMillis(Objects.nonNull(memory.getUpdatedAt())
                ? memory.getUpdatedAt() : memory.getCreatedAt()));
        vo.setCreatedAt(TimeUtil.toMillis(memory.getCreatedAt()));
        return vo;
    }
}
