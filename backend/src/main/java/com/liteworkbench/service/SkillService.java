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

    public PageResult<SkillVO> page(PageREQ req, String userId) {
        long total = skillMapper.countPage(req, userId);
        if (total <= 0) {
            return PageResult.empty(req.pageNo(), req.pageSize());
        }
        List<SkillVO> list = skillMapper.selectPage(req, userId, req.offset(), req.pageSize())
                .stream().map(this::toVO).toList();
        return PageResult.of(list, total, req.pageNo(), req.pageSize());
    }

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

    /** 平台内置技能种子：内容是操作手册而非提示词，命中触发词才展开正文，节省上下文 */
    public record BuiltinSkillSeed(String code, String name, String summary, String content, String triggerWords) {
    }

    private static final String OUTLINE_CONTENT = """
            目标：避免直接开写导致结构失控。
            步骤：
            1. 先用 task_plan 列出写作计划（提纲 → 确认 → 逐节成文 → 通读校对）。
            2. 产出一版大纲：一级标题 + 每节要回答的一个问题，不超过 8 节。
            3. 用户确认前不写正文；用户直接给全文任务时，先给大纲再在同一次回答里成文。
            4. 成文时每节围绕该节的那一个问题，跑题的素材舍弃。
            输出：大纲用有序列表；正文用 Markdown 二级标题分节。""";

    private static final String FACT_CITE_CONTENT = """
            目标：所有关于用户资料的断言都可溯源。
            步骤：
            1. 提炼问题里的可检索关键词（人名、产品名、数字、术语），用 kb_search 检索，必要时换 2 到 3 组关键词。
            2. 只依据检索返回的片段作答；每个关键断言后标注来源，格式：（来源：文档名 · 块 3）。
            3. 检索不到或片段不足以支撑结论时，明确说「资料中没有找到」，并说明缺什么。
            禁止：把常识包装成来自用户资料的结论；拼接不同文档的片段制造伪出处。""";

    private static final String POLISH_CONTENT = """
            目标：改善表达质量，不改变作者的观点与事实内容。
            步骤：
            1. 第一遍看结构：段落顺序、逻辑衔接、开头是否拖沓，调整而不重写。
            2. 第二遍看节奏：拆长句、删冗余词、统一语气与人称。
            3. 第三遍看用词：替换含糊表述，专有名词与数字必须原样保留。
            输出：先给润色后全文，再用不超过 5 条列表说明改了什么、为什么改。原文中的数字、名称、引用一律不改。""";

    private static final String DATA_BRIEF_CONTENT = """
            目标：让每个数字都可复算。
            步骤：
            1. 用 python_exec 做计算与汇总，脚本一次算完并打印全部中间量，便于核对。
            2. 对关键结论做一次反向校验（如合计、占比归一），对不上就回查脚本。
            3. 输出：结论（不超过 3 条）→ Markdown 表格（原始数据或汇总）→ 计算口径说明。
            禁止：口算后直接给数；单位与百分比必须写清；样本量不足时说明可信度。""";

    private static final String DOC_DIGEST_CONTENT = """
            目标：摘要能替代原文做决策，而不是目录复述。
            步骤：
            1. 长文在工作区时先用 file_read 读取；在知识库里时用 kb_search 取相关块。
            2. 按四段输出：一句话结论 / 关键要点（不超过 5 条）/ 关键数字与日期 / 风险与未决问题。
            3. 每条要点注明出自原文的位置；原文没有的内容不写。
            长度：默认不超过 400 字，用户给定时以其为准。""";

    public static final List<BuiltinSkillSeed> BUILTIN_SKILLS = List.of(
            new BuiltinSkillSeed("outline_first", "先纲后文",
                    "写作前先产出大纲并与用户对齐，再逐节展开成文",
                    OUTLINE_CONTENT, "大纲,提纲,结构,outline,写一篇文章,写篇文章"),
            new BuiltinSkillSeed("fact_cite", "取证引用",
                    "涉及用户资料时先检索知识库并标注来源，禁止编造",
                    FACT_CITE_CONTENT, "引用,出处,来源,资料,依据,考证,知识库里有"),
            new BuiltinSkillSeed("polish_pass", "三遍润色",
                    "按结构、节奏、用词三遍润色，保持原意与事实不变",
                    POLISH_CONTENT, "润色,改写,优化文笔,polish,改一下文字"),
            new BuiltinSkillSeed("data_brief", "数据简报",
                    "用 python_exec 计算并校验数字，输出表格化简报",
                    DATA_BRIEF_CONTENT, "数据,统计,汇总,报表,占比,环比,同比"),
            new BuiltinSkillSeed("doc_digest", "长文摘要",
                    "读全文后按固定结构产出摘要，保留关键结论与数字",
                    DOC_DIGEST_CONTENT, "摘要,总结一下,digest,概括,读一下这篇"));

    /** 为用户补种内置技能：按 (userId, skillCode) 全量判重（含已删行），用户删过的不会复活 */
    public void seedBuiltinForUser(String userId) {
        int created = 0;
        int marked = 0;
        for (BuiltinSkillSeed seed : BUILTIN_SKILLS) {
            String anyId = skillMapper.selectAnyIdByCode(userId, seed.code());
            if (Objects.nonNull(anyId)) {
                // 升级前补种的行没有 builtin 列，这里补标记；内容仍是用户版本，不做覆盖
                marked += skillMapper.markBuiltin(anyId, userId);
                continue;
            }
            WbSkillDO skill = new WbSkillDO();
            skill.setSkillId(IdGenerator.generate(IdPrefixConst.SKILL));
            skill.setUserId(userId);
            skill.setSkillCode(seed.code());
            skill.setSkillName(seed.name());
            skill.setSummary(seed.summary());
            skill.setContent(seed.content());
            skill.setTriggerWords(seed.triggerWords());
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
        BuiltinSkillSeed seed = BUILTIN_SKILLS.stream()
                .filter(item -> Objects.equals(item.code(), skill.getSkillCode()))
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCodeEnum.PARAM_INVALID, "内置技能种子不存在：" + skill.getSkillCode()));
        skill.setSkillName(seed.name());
        skill.setSummary(seed.summary());
        skill.setContent(seed.content());
        skill.setTriggerWords(seed.triggerWords());
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
