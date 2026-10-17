package com.liteworkbench.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.IdGenerator;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.constant.IdPrefixConst;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.dto.SkillREQ;
import com.liteworkbench.domain.entity.WbSkillDO;
import com.liteworkbench.domain.entity.WbSkillSeedDO;
import com.liteworkbench.domain.vo.SkillVO;
import com.liteworkbench.mapper.WbSkillMapper;
import com.liteworkbench.mapper.WbSkillSeedMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/** 技能：两段式（摘要常驻 + 命中展开），管理 CRUD 与提示词装配 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SkillService {

    /** 触发词上限 */
    private static final int MAX_TRIGGER_WORDS = 20;
    /** 单轮最多展开技能数 */
    private static final int MAX_EXPANDED = 3;

    private final WbSkillMapper skillMapper;
    private final WbSkillSeedMapper skillSeedMapper;
    private final AgentProperties agentProperties;

    /** 分页列出当前用户的技能 */
    public PageResult<SkillVO> page(PageREQ req, String userId) {
        long total = skillMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<SkillVO> list = skillMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 新建技能：编码唯一，正文受长度上限约束 */
    public String create(SkillREQ req, String userId) {
        validate(req);
        if (Objects.nonNull(skillMapper.selectByCode(userId, req.getSkillCode()))) {
            throw new BizException(ErrorCodeEnum.SKILL_CODE_CONFLICT, req.getSkillCode());
        }
        WbSkillDO skill = new WbSkillDO();
        skill.setSkillId(IdGenerator.generate(IdPrefixConst.SKILL));
        skill.setUserId(userId);
        fill(req, skill);
        skill.setCreatedBy(userId);
        skillMapper.insert(skill);
        log.info("[skill.create] userId={} skillId={} code={}", userId, skill.getSkillId(), skill.getSkillCode());
        return skill.getSkillId();
    }

    /** 更新技能 */
    public void update(SkillREQ req, String userId) {
        if (StrUtil.isBlank(req.getSkillId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "skillId 不能为空");
        }
        validate(req);
        getOwned(req.getSkillId(), userId);
        var sameCode = skillMapper.selectByCode(userId, req.getSkillCode());
        if (Objects.nonNull(sameCode) && !Objects.equals(sameCode.getSkillId(), req.getSkillId())) {
            throw new BizException(ErrorCodeEnum.SKILL_CODE_CONFLICT, req.getSkillCode());
        }
        WbSkillDO skill = new WbSkillDO();
        skill.setSkillId(req.getSkillId());
        skill.setUserId(userId);
        fill(req, skill);
        skill.setUpdatedBy(userId);
        skillMapper.update(skill);
    }

    /** 删除技能 */
    public void delete(String skillId, String userId) {
        getOwned(skillId, userId);
        skillMapper.deleteById(userId, skillId, userId);
    }

    /** 装配系统提示词：原提示词 + 启用技能摘要；用户点名（forcedCodes）与命中触发词的技能展开正文 */
    public String assembleSystemPrompt(String userId, String basePrompt, String userInput, List<String> forcedCodes) {
        if (!agentProperties.getSkill().isEnabled()) {
            return basePrompt;
        }
        List<WbSkillDO> skills = skillMapper.selectEnabledByUser(userId);
        if (skills.isEmpty()) {
            return basePrompt;
        }
        List<String> forced = CollUtil.emptyIfNull(forcedCodes);
        StringBuilder prompt = new StringBuilder(StrUtil.nullToEmpty(basePrompt));
        prompt.append("\n\n[可用技能] 命中触发词或输入里点名 /code 时会自动展开对应操作手册：");
        skills.forEach(skill -> prompt.append('\n').append("- ").append(skill.getSkillCode())
                .append("：").append(StrUtil.nullToEmpty(skill.getSummary())));

        List<WbSkillDO> named = skills.stream()
                .filter(skill -> forced.contains(skill.getSkillCode()))
                .limit(MAX_EXPANDED)
                .toList();
        List<WbSkillDO> matched = skills.stream()
                .filter(skill -> !forced.contains(skill.getSkillCode()))
                .filter(skill -> match(skill, userInput))
                .limit(MAX_EXPANDED - named.size())
                .toList();
        CollUtil.newArrayList(named).forEach(skill -> appendSkill(prompt, skill));
        matched.forEach(skill -> appendSkill(prompt, skill));
        return prompt.toString();
    }

    private static void appendSkill(StringBuilder prompt, WbSkillDO skill) {
        prompt.append("\n\n[已展开技能：").append(skill.getSkillCode()).append("]\n")
                .append(StrUtil.nullToEmpty(skill.getContent()));
    }

    /** 触发词匹配：忽略大小写的包含判断，无触发词的技能不命中 */
    private boolean match(WbSkillDO skill, String userInput) {
        if (StrUtil.isBlank(skill.getTriggerWords()) || StrUtil.isBlank(userInput)) {
            return false;
        }
        String lower = userInput.toLowerCase();
        return Arrays.stream(skill.getTriggerWords().split("[,，]"))
                .map(String::trim).filter(StrUtil::isNotBlank)
                .anyMatch(word -> lower.contains(word.toLowerCase()));
    }

    /** 为用户补种内置技能：按 (userId, skillCode) 全量判重（含已删行），用户删过的不会复活 */
    public void seedBuiltinForUser(String userId) {
        int created = 0;
        int marked = 0;
        for (WbSkillSeedDO seed : skillSeedMapper.selectAll()) {
            String anyId = skillMapper.selectAnyIdByCode(userId, seed.getSkillCode());
            if (Objects.nonNull(anyId)) {
                // 升级前补种的行没有 builtin 列，这里补标记；内容仍是用户版本，不做覆盖
                marked += skillMapper.markBuiltin(anyId, userId);
                continue;
            }
            WbSkillDO skill = new WbSkillDO();
            skill.setSkillId(IdGenerator.generate(IdPrefixConst.SKILL));
            skill.setUserId(userId);
            skill.setSkillCode(seed.getSkillCode());
            skill.setSkillName(seed.getSkillName());
            skill.setSummary(seed.getSummary());
            skill.setContent(seed.getContent());
            skill.setTriggerWords(seed.getTriggerWords());
            skill.setEnabled(Boolean.TRUE);
            skill.setBuiltin(Boolean.TRUE);
            skill.setCreatedBy(userId);
            skill.setRemark("builtin");
            skillMapper.insert(skill);
            created++;
        }
        if (created > 0 || marked > 0) {
            log.info("[skill.seed] userId={} created={} marked={}", userId, created, marked);
        }
    }

    /** 恢复内置技能为种子版本：仅内置技能可恢复，保留用户的启用状态 */
    public void restoreBuiltin(String skillId, String userId) {
        WbSkillDO skill = getOwned(skillId, userId);
        if (!Boolean.TRUE.equals(skill.getBuiltin())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "仅内置技能可以恢复默认");
        }
        WbSkillSeedDO seed = skillSeedMapper.selectByCode(skill.getSkillCode());
        if (Objects.isNull(seed)) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "内置技能种子不存在：" + skill.getSkillCode());
        }
        skill.setSkillName(seed.getSkillName());
        skill.setSummary(seed.getSummary());
        skill.setContent(seed.getContent());
        skill.setTriggerWords(seed.getTriggerWords());
        skill.setRemark("builtin");
        skill.setUpdatedBy(userId);
        skillMapper.restoreBuiltin(skill);
        log.info("[skill.restore] userId={} skillId={} code={}", userId, skillId, skill.getSkillCode());
    }

    private void validate(SkillREQ req) {
        if (StrUtil.isNotBlank(req.getTriggerWords())
                && req.getTriggerWords().split("[,，]").length > MAX_TRIGGER_WORDS) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "触发词最多 20 个");
        }
    }

    private void fill(SkillREQ req, WbSkillDO skill) {
        skill.setSkillCode(req.getSkillCode());
        skill.setSkillName(req.getSkillName());
        skill.setSummary(req.getSummary());
        skill.setContent(req.getContent());
        skill.setTriggerWords(req.getTriggerWords());
        skill.setEnabled(Objects.nonNull(req.getEnabled()) ? req.getEnabled() : Boolean.TRUE);
    }

    private WbSkillDO getOwned(String skillId, String userId) {
        WbSkillDO skill = skillMapper.selectByIdAndUser(userId, skillId);
        if (Objects.isNull(skill)) {
            throw new BizException(ErrorCodeEnum.SKILL_NOT_FOUND, skillId);
        }
        return skill;
    }

    private SkillVO toVO(WbSkillDO skill) {
        SkillVO vo = new SkillVO();
        vo.setSkillId(skill.getSkillId());
        vo.setSkillCode(skill.getSkillCode());
        vo.setSkillName(skill.getSkillName());
        vo.setSummary(skill.getSummary());
        vo.setContent(skill.getContent());
        vo.setTriggerWords(skill.getTriggerWords());
        vo.setEnabled(skill.getEnabled());
        vo.setBuiltin(Boolean.TRUE.equals(skill.getBuiltin()));
        vo.setCreatedAt(TimeUtil.toMillis(skill.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(skill.getUpdatedAt()));
        return vo;
    }
}
