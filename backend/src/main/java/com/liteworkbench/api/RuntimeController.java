package com.liteworkbench.api;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.vo.PythonStatusVO;
import com.liteworkbench.service.MonitorService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 内置 Python 运行时接口：探测结果与重新探测 */
@RestController
@RequestMapping("/api/v1/runtime")
@RequiredArgsConstructor
public class RuntimeController {

    private final MonitorService monitorService;

    @SaCheckPermission(PermConst.MONITOR_HEALTH_LIST)
    @GetMapping("/python")
    public R<PythonStatusVO> pythonStatus() {
        return R.ok(monitorService.pythonStatus());
    }

    @Idempotent(prefix = "runtime:reprobe", ttl = 5)
    @SaCheckPermission(PermConst.MONITOR_PYTHON_REPROBE)
    @PostMapping("/python/reprobe")
    public R<PythonStatusVO> reprobe() {
        monitorService.reprobePython();
        return R.ok(monitorService.pythonStatus());
    }
}
