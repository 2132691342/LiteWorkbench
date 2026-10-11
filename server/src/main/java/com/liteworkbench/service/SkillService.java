package com.liteworkbench.service;

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
import com.liteworkbench.domain.vo.SkillVO;
import com.liteworkbench.mapper.WbSkillMapper;
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
    private final AgentProperties agentProperties;

    /** 分页查询 */
    public PageResult<SkillVO> page(PageREQ req, String userId) {
        long total = skillMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<SkillVO> list = skillMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

    /** 新建技能 */
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

    /** 装配系统提示词：原提示词 + 启用技能摘要；命中触发词的正文一并列出 */
    public String assembleSystemPrompt(String userId, String basePrompt, String userInput) {
        if (!agentProperties.getSkill().isEnabled()) {
            return basePrompt;
        }
        List<WbSkillDO> skills = skillMapper.selectEnabledByUser(userId);
        if (skills.isEmpty()) {
            return basePrompt;
        }
        StringBuilder prompt = new StringBuilder(StrUtil.nullToEmpty(basePrompt));
        prompt.append("\n\n[可用技能] 命中触发词时会自动展开对应操作手册：");
        skills.forEach(skill -> prompt.append('\n').append("- ").append(skill.getSkillCode())
                .append("：").append(StrUtil.nullToEmpty(skill.getSummary())));

        List<WbSkillDO> matched = skills.stream()
                .filter(skill -> match(skill, userInput))
                .limit(MAX_EXPANDED)
                .toList();
        matched.forEach(skill -> prompt.append("\n\n[已展开技能：").append(skill.getSkillCode()).append("]\n")
                .append(StrUtil.nullToEmpty(skill.getContent())));
        return prompt.toString();
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
        vo.setCreatedAt(TimeUtil.toMillis(skill.getCreatedAt()));
        vo.setUpdatedAt(TimeUtil.toMillis(skill.getUpdatedAt()));
        return vo;
    }


}
