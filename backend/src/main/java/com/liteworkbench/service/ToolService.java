package com.liteworkbench.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.agent.tool.ToolRegistry;
import com.liteworkbench.agent.tool.WorkbenchTools;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.ToolRiskEnum;
import com.liteworkbench.domain.dto.InvokeToolREQ;
import com.liteworkbench.domain.entity.WbToolDO;
import com.liteworkbench.domain.vo.ToolVO;
import com.liteworkbench.mapper.WbToolMapper;
import com.liteworkbench.runtime.PythonRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 工具：列表、启停与页面手工调试执行 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    /** 页面手工调用未指定超时时的默认值，秒 */
    private static final long DEFAULT_INVOKE_TIMEOUT_SECONDS = 120L;

    private final WbToolMapper toolMapper;
    private final ToolGate toolGate;
    private final KbSearchPort kbSearchPort;
    private final PythonRuntime pythonRuntime;
    private final RuntimeProperties runtimeProperties;

    /** 工具列表 */
    public List<ToolVO> list() {
        return toolMapper.selectAll().stream().map(this::toVO).toList();
    }

    /** 启停，全局生效 */
    public void toggle(String toolId, boolean enabled) {
        WbToolDO tool = toolMapper.selectById(toolId);
        if (Objects.isNull(tool)) {
            throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, toolId);
        }
        toolMapper.updateEnabled(toolId, enabled);
        log.info("[tool.toggle] toolId={} name={} enabled={}", toolId, tool.getToolName(), enabled);
    }

    /** 页面手工执行：与 Agent 内调用共用同一注册表、同一超时与同一输出裁剪 */
    public String invoke(String toolName, InvokeToolREQ req) {
        String userId = LoginContext.userId();
        WbToolDO tool = toolMapper.selectByName(toolName);
        if (Objects.isNull(tool)) {
            throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, toolName);
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new BizException(ErrorCodeEnum.TOOL_DISABLED, toolName);
        }
        if (!ToolRiskEnum.SAFE.getCode().equals(tool.getRiskLevel()) && !Boolean.TRUE.equals(req.getConfirmed())) {
            // confirm / danger 工具有副作用，页面必须显式确认；Agent 路径由平台级启用开关统一闸门
            throw new BizException(ErrorCodeEnum.TOOL_CONFIRM_REQUIRED, tool.getToolLabel());
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (CollUtil.isNotEmpty(req.getArgs())) {
            args.putAll(req.getArgs());
        }
        long timeoutSeconds = resolveTimeout(req);
        // 页面未指定超时时把归一后的值显式传给工具，避免工具自身默认值与页面语义不一致
        args.put("timeoutSeconds", timeoutSeconds);
        ToolRegistry registry = new ToolRegistry();
        registry.register(new WorkbenchTools(toolGate, kbSearchPort, pythonRuntime, runtimeProperties, userId));
        long start = System.currentTimeMillis();
        try {
            String output = registry.invoke(toolName, JSONUtil.toJsonStr(args), timeoutSeconds);
            log.info("[tool.invoke] toolName={} userId={} cost={}ms", toolName, userId,
                    System.currentTimeMillis() - start);
            return output;
        } finally {
            registry.shutdown();
        }
    }

    private long resolveTimeout(InvokeToolREQ req) {
        return Objects.nonNull(req.getTimeoutSeconds()) && req.getTimeoutSeconds() > 0
                ? req.getTimeoutSeconds() : DEFAULT_INVOKE_TIMEOUT_SECONDS;
    }

    private ToolVO toVO(WbToolDO tool) {
        ToolVO vo = new ToolVO();
        vo.setToolId(tool.getToolId());
        vo.setToolName(tool.getToolName());
        vo.setToolLabel(tool.getToolLabel());
        vo.setCategory(tool.getCategory());
        vo.setRiskLevel(tool.getRiskLevel());
        vo.setEnabled(tool.getEnabled());
        return vo;
    }
}
