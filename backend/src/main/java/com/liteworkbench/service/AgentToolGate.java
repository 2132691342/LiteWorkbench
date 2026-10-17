package com.liteworkbench.service;

import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.domain.entity.WbToolDO;
import com.liteworkbench.mapper.WbToolMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** 工具启停查询的 service 侧实现，供 agent 装配时读取平台级配置 */
@Component
@RequiredArgsConstructor
public class AgentToolGate implements ToolGate {

    private final WbToolMapper toolMapper;

    /** 工具是否在平台侧启用 */
    @Override
    public boolean isEnabled(String toolName) {
        WbToolDO tool = toolMapper.selectByName(toolName);
        return Objects.nonNull(tool) && Boolean.TRUE.equals(tool.getEnabled());
    }
}
