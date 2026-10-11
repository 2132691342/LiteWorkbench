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

/** 技能装配：命中触发词才展开正文、摘要常驻、单轮最多展开 3 条、总开关旁路 */
class SkillServiceTest {

    private WbSkillMapper mapper;
    private SkillService service;

    @BeforeEach
    void setUp() {
        mapper = Mockito.mock(WbSkillMapper.class);
        service = new SkillService(mapper, new AgentProperties());
    }

    @Test
    void 命中触发词展开正文未命中只留摘要() {
        Mockito.when(mapper.selectEnabledByUser("USER_1")).thenReturn(List.of(
                skill("code_a", "A 摘要", "A 正文", "报告,分析"),
                skill("code_b", "B 摘要", "B 正文", "图表")));
        String prompt = service.assembleSystemPrompt("USER_1", "基础提示词", "帮我做一份分析报告");
        assertTrue(prompt.contains("基础提示词"));
        assertTrue(prompt.contains("A 正文"));
        assertTrue(prompt.contains("B 摘要"), "未命中技能摘要常驻");
        assertFalse(prompt.contains("B 正文"), "未命中技能正文不占用上下文");
    }

    @Test
    void 单轮最多展开三条技能() {
        Mockito.when(mapper.selectEnabledByUser("USER_1")).thenReturn(List.of(
                skill("s1", "1", "正文1", "词"),
                skill("s2", "2", "正文2", "词"),
                skill("s3", "3", "正文3", "词"),
                skill("s4", "4", "正文4", "词")));
        String prompt = service.assembleSystemPrompt("USER_1", null, "含触发词的输入");
        assertTrue(prompt.contains("正文3"));
        assertFalse(prompt.contains("正文4"));
    }

    @Test
    void 总开关关闭即不装配且不查库() {
        AgentProperties properties = new AgentProperties();
        properties.getSkill().setEnabled(false);
        SkillService disabled = new SkillService(mapper, properties);
        String prompt = disabled.assembleSystemPrompt("USER_1", "基础", "分析报告");
        Mockito.verifyNoInteractions(mapper);
        assertTrue(prompt.contains("基础"));
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
