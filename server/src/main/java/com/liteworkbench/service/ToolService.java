package com.liteworkbench.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ToolOutputCapture;
import com.liteworkbench.agent.WorkbenchTools;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
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

    /** 页面手工执行，与 Agent 内调用共用同一实现与裁剪策略 */
    public String invoke(String toolName, InvokeToolREQ req, String userId) {
        WbToolDO tool = toolMapper.selectByName(toolName);
        if (Objects.isNull(tool)) {
            throw new BizException(ErrorCodeEnum.TOOL_NOT_FOUND, toolName);
        }
        if (!Boolean.TRUE.equals(tool.getEnabled())) {
            throw new BizException(ErrorCodeEnum.TOOL_DISABLED, toolName);
        }
        if ("danger".equals(tool.getRiskLevel())) {
            throw new BizException(ErrorCodeEnum.FORBIDDEN, "危险工具需页面确认流程，暂不支持手工执行");
        }
        if ("confirm".equals(tool.getRiskLevel()) && !Boolean.TRUE.equals(req.getConfirmed())) {
            throw new BizException(ErrorCodeEnum.FORBIDDEN, "该工具有副作用，需要显式确认");
        }
        Map<String, Object> args = req.getArgs();
        WorkbenchTools tools = new WorkbenchTools(toolMapper, pythonRuntime, runtimeProperties,
                new ToolOutputCapture());
        long start = System.currentTimeMillis();
        String output = switch (toolName) {
            case "python_exec" -> tools.pythonExec(str(args, "code"),
                    Objects.nonNull(req.getTimeoutSeconds()) ? req.getTimeoutSeconds() : intArg(args, "timeoutSeconds"));
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

    private Integer intArg(Map<String, Object> args, String key) {
        if (CollUtil.isEmpty(args)) {
            return null;
        }
        Object value = args.get(key);
        return value instanceof Number number ? number.intValue() : null;
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
