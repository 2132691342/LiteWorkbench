package com.liteworkbench.api;

import cn.hutool.json.JSONUtil;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.InvokeToolREQ;
import com.liteworkbench.domain.vo.ToolVO;
import com.liteworkbench.service.ToolService;
import cn.dev33.satoken.annotation.SaCheckPermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工具接口：列表、启停与手工调试执行 */
@RestController
@RequestMapping("/api/v1/tool")
@RequiredArgsConstructor
public class ToolController {

    private final ToolService toolService;

    /** 工具列表 */
    @SaCheckPermission(PermConst.WB_TOOL_LIST)
    @GetMapping("/list")
    public R<List<ToolVO>> list() {
        return R.ok(toolService.list());
    }

    /** 启停 */
    @SaCheckPermission(PermConst.WB_TOOL_TOGGLE)
    @Idempotent(prefix = "tool:toggle")
    @PostMapping("/toggle/{toolId}")
    public R<Void> toggle(@PathVariable String toolId, @org.springframework.web.bind.annotation.RequestParam boolean enabled) {
        toolService.toggle(toolId, enabled);
        return R.ok();
    }

    /** 手工执行，返回工具原始输出 */
    @SaCheckPermission(PermConst.WB_TOOL_INVOKE)
    @Idempotent(prefix = "tool:invoke")
    @PostMapping("/invoke/{toolName}")
    public R<String> invoke(@PathVariable String toolName, @RequestBody @Valid InvokeToolREQ req) {
        return R.ok(toolService.invoke(toolName, req, LoginContext.userId()));
    }
}
