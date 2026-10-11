package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.FlowREQ;
import com.liteworkbench.domain.dto.FlowRunREQ;
import com.liteworkbench.domain.dto.PageREQ;
import com.liteworkbench.domain.vo.FlowRunVO;
import com.liteworkbench.domain.vo.FlowVO;
import com.liteworkbench.service.FlowEngine;
import com.liteworkbench.service.FlowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 工作流接口：画布 CRUD、结构校验与串行执行 */
@RestController
@RequestMapping("/api/v1/flow")
@RequiredArgsConstructor
public class FlowController {

    private final FlowService flowService;
    private final FlowEngine flowEngine;

    /** 分页 */
    @SaCheckPermission(PermConst.WB_FLOW_LIST)
    @GetMapping("/page")
    public R<PageResult<FlowVO>> page(PageREQ req) {
        return R.ok(flowService.page(req));
    }

    /** 详情，含画布结构 */
    @SaCheckPermission(PermConst.WB_FLOW_LIST)
    @GetMapping("/detail/{flowId}")
    public R<FlowVO> detail(@PathVariable String flowId) {
        return R.ok(flowService.detail(flowId));
    }

    /** 新建 */
    @SaCheckPermission(PermConst.WB_FLOW_CREATE)
    @Idempotent(prefix = "flow:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid FlowREQ req) {
        return R.ok(flowService.create(req));
    }

    /** 保存画布，结构不合法即拒绝 */
    @SaCheckPermission(PermConst.WB_FLOW_UPDATE)
    @Idempotent(prefix = "flow:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid FlowREQ req) {
        flowService.update(req);
        return R.ok();
    }

    /** 删除 */
    @SaCheckPermission(PermConst.WB_FLOW_DELETE)
    @Idempotent(prefix = "flow:delete")
    @PostMapping("/delete/{flowId}")
    public R<Void> delete(@PathVariable String flowId) {
        flowService.delete(flowId);
        return R.ok();
    }

    /** 执行工作流，同步返回逐节点结果 */
    @SaCheckPermission(PermConst.WB_FLOW_RUN)
    @PostMapping("/run")
    public R<FlowRunVO> run(@RequestBody @Valid FlowRunREQ req) {
        String userId = LoginContext.userId();
        return R.ok(flowEngine.run(flowService.getOwned(req.getFlowId(), userId), req.getInputs()));
    }

    /** 运行明细：从事件流还原节点级输出 */
    @SaCheckPermission(PermConst.WB_FLOW_LIST)
    @GetMapping("/run/detail/{runId}")
    public R<FlowRunVO> runDetail(@PathVariable String runId) {
        return R.ok(flowEngine.detail(runId, LoginContext.userId()));
    }
}
