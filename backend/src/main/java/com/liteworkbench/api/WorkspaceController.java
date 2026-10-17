package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.WorkspaceREQ;
import com.liteworkbench.domain.vo.WorkspaceVO;
import com.liteworkbench.service.WorkspaceService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 工作区接口：登录即可用，按 user_id 归属隔离；不同工作区对应不同 MinIO 桶 */
@RestController
@RequestMapping("/api/v1/workspace")
@RequiredArgsConstructor
public class WorkspaceController {

    private final WorkspaceService workspaceService;

    /** 当前用户工作区列表，含桶占用统计 */
    @GetMapping("/list")
    public R<List<WorkspaceVO>> list() {
        return R.ok(workspaceService.list(LoginContext.userId()));
    }

    /** 新建工作区，幂等建桶 */
    @Idempotent(prefix = "workspace:create")
    @PostMapping("/create")
    public R<String> create(@RequestBody @Valid WorkspaceREQ req) {
        return R.ok(workspaceService.create(req, LoginContext.userId()));
    }

    /** 修改名称与描述 */
    @Idempotent(prefix = "workspace:update")
    @PostMapping("/update")
    public R<Void> update(@RequestBody @Valid WorkspaceREQ req) {
        workspaceService.update(req, LoginContext.userId());
        return R.ok();
    }

    /** 设为默认工作区 */
    @Idempotent(prefix = "workspace:default")
    @PostMapping("/default/{workspaceId}")
    public R<Void> setDefault(@PathVariable String workspaceId) {
        workspaceService.setDefault(workspaceId, LoginContext.userId());
        return R.ok();
    }

    /** 删除工作区，默认工作区不可删 */
    @Idempotent(prefix = "workspace:delete")
    @PostMapping("/delete/{workspaceId}")
    public R<Void> delete(@PathVariable String workspaceId) {
        workspaceService.delete(workspaceId, LoginContext.userId());
        return R.ok();
    }
}
