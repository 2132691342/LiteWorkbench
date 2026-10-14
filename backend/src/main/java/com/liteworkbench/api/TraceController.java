package com.liteworkbench.api;

import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.TraceQueryREQ;
import com.liteworkbench.domain.vo.TraceRunVO;
import com.liteworkbench.domain.vo.TraceSpanVO;
import com.liteworkbench.domain.vo.UsageStatsVO;
import com.liteworkbench.service.TraceService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 可观测性接口：链路片段、Run 树、用量归因；数据全为本人所有，跨用户视角由服务端按角色判定 */
@RestController
@RequestMapping("/api/v1/trace")
@RequiredArgsConstructor
public class TraceController {

    private final TraceService traceService;

    /** 单次运行的链路片段，供瀑布图绘制 */
    @GetMapping("/spans/{runId}")
    public R<List<TraceSpanVO>> spans(@PathVariable String runId) {
        return R.ok(traceService.spans(runId));
    }

    /** 会话内的 Run 树，含派生的子运行 */
    @GetMapping("/tree/{sessionId}")
    public R<List<TraceRunVO>> tree(@PathVariable String sessionId) {
        return R.ok(traceService.runTree(sessionId));
    }

    /** 用量归因：概览 + 按天 / 按模型 / 按状态分解 */
    @GetMapping("/usage")
    public R<UsageStatsVO> usage(TraceQueryREQ req) {
        return R.ok(traceService.usage(req));
    }
}
