package com.liteworkbench.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ToolOutputCapture;
import com.liteworkbench.agent.WorkbenchTools;
import com.liteworkbench.agent.spi.ToolGate;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.config.RuntimeProperties;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.InvokeToolREQ;
import com.liteworkbench.domain.entity.WbToolDO;
import com.liteworkbench.domain.vo.ToolVO;
import com.liteworkbench.mapper.WbToolMapper;
import com.liteworkbench.runtime.PythonRuntime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 工具：列表、启停与页面手工调试执行 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolService {

    private final WbToolMapper toolMapper;
    private final ToolGate toolGate;
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

    /** 页面手工执行，与 Agent 内调用共用同一实现与裁剪策略，工作目录按当前用户隔离 */
    public String invoke(String toolName, InvokeToolREQ req) {
        String userId = LoginContext.userId();
        WbToolDO tool = toolMapper.selectByName(toolName);
        if (Objects.isNull(tool)) {
            throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, toolName);
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new BizException(ErrorCodeEnum.TOOL_DISABLED, toolName);
        }
        if (!"safe".equals(tool.getRiskLevel()) && !Boolean.TRUE.equals(req.getConfirmed())) {
            // confirm / danger 工具有副作用，页面必须显式确认；Agent 路径由启用开关统一闸门
            throw new BizException(ErrorCodeEnum.TOOL_CONFIRM_REQUIRED, tool.getToolLabel());
        }
        Map<String, Object> args = req.getArgs();
        WorkbenchTools tools = new WorkbenchTools(toolGate, pythonRuntime, runtimeProperties,
                new ToolOutputCapture(), userId);
        long start = System.currentTimeMillis();
        String output = switch (toolName) {
            case "python_exec" -> {
                Integer argTimeout = CollUtil.isEmpty(args) ? null
                        : (args.get("timeoutSeconds") instanceof Number number ? number.intValue() : null);
                yield tools.pythonExec(str(args, "code"),
                        Objects.nonNull(req.getTimeoutSeconds()) ? req.getTimeoutSeconds() : argTimeout);
            }
            case "file_read" -> tools.fileRead(str(args, "path"));
            case "file_write" -> tools.fileWrite(str(args, "path"), str(args, "content"));
            case "http_get" -> tools.httpGet(str(args, "url"));
            default -> throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, toolName);
        };
        log.info("[tool.invoke] toolName={} userId={} cost={}ms", toolName, userId, System.currentTimeMillis() - start);
        return output;
    }

    private String str(Map<String, Object> args, String key) {
        if (CollUtil.isEmpty(args)) {
            return null;
        }
        Object value = args.get(key);
        return Objects.toString(value, null);
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
