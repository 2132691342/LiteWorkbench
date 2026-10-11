package com.liteworkbench.service;

import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.domain.entity.WbSkillDO;
import com.liteworkbench.mapper.WbSkillMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.mockito.ArgumentMatchers;

/** 技能触发词匹配与提示词装配：忽略大小写包含判断，单轮最多展开 3 条 */
class SkillServiceTest {

    private WbSkillMapper mapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(WbSkillMapper.class);
        service = new SkillService(mapper, new AgentProperties());
    }

    @Test
    void matchedSkillsShouldExpandContent() {
        Mockito.when(mapper.selectEnabledByUser("USER_1")).thenReturn(List.of(
                skill("code_a", "A 摘要", "A 正文", "报告,分析"),
                skill("code_b", "B 摘要", "B 正文", "图表")));
        String prompt = service.assembleSystemPrompt("USER_1", "基础提示词", "帮我做一份分析报告");
        assertTrue(prompt.contains("基础提示词"));
        assertTrue(prompt.contains("A 摘要"));
        assertTrue(prompt.contains("A 正文"), "命中触发词的正文必须展开");
        assertFalse(prompt.contains("B 正文"), "未命中技能正文不占用上下文");
    }

    @Test
    void matchingShouldIgnoreCase() {
        Mockito.when(mapper.selectEnabledByUser("USER_1")).thenReturn(List.of(
                skill("code_c", "C 摘要", "C 正文", "SQL")));
        String prompt = service.assembleSystemPrompt("USER_1", null, "写一段sql统计");
        assertTrue(prompt.contains("C 正文"));
    }

    @Test
    void atMostThreeSkillsExpanded() {
        Mockito.when(mapper.selectEnabledByUser("USER_1")).thenReturn(List.of(
                skill("s1", "1", "正文1", "词"),
                skill("s2", "2", "正文2", "词"),
                skill("s3", "3", "正文3", "词"),
                skill("s4", "4", "正文4", "词")));
        String prompt = service.assembleSystemPrompt("USER_1", null, "含触发词的输入");
        assertFalse(prompt.contains("正文4"), "最多展开 3 条技能");
    }

    @Test
    void disabledSkillFlagShouldBypassExpansion() {
        AgentProperties properties = new AgentProperties();
        properties.getSkill().setEnabled(false);
        SkillService disabled = new SkillService(mapper, properties);
        String prompt = disabled.assembleSystemPrompt("USER_1", "基础", "分析报告");
        Mockito.verifyNoInteractions(mapper);
        assertTrue(prompt.contains("基础"));
    }

    @Test
    void summaryListAlwaysPresentWhenEnabled() {
        Mockito.when(mapper.selectEnabledByUser(ArgumentMatchers.anyString())).thenReturn(List.of(
                skill("code_d", "D 摘要", "D 正文", "图表")));
        String prompt = service.assembleSystemPrompt("USER_1", null, "与技能无关的输入");
        assertTrue(prompt.contains("D 摘要"), "摘要常驻提示词");
        assertFalse(prompt.contains("D 正文"));
    }

    private WbSkillDO skill(String code, String summary, String content, String triggers) {
        WbSkillDO skill = new WbSkillDO();
        skill.setSkillCode(code);
        skill.setSummary(summary);
        skill.setContent(content);
        skill.setTriggerWords(triggers);
        return skill;
    }
}
