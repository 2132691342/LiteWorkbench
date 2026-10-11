package com.liteworkbench.api;

import com.liteworkbench.anno.Idempotent;
import com.liteworkbench.common.PageResult;
import com.liteworkbench.common.R;
import com.liteworkbench.domain.dto.ChatStreamREQ;
import com.liteworkbench.domain.dto.SessionPageREQ;
import com.liteworkbench.domain.dto.RunEventDTO;
import com.liteworkbench.domain.vo.RunVO;
import com.liteworkbench.service.RunService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/** 对话与运行接口：SSE 流式、回放与中断 */
@RestController
@RequiredArgsConstructor
public class RunController {

    private final RunService runService;

    /** 流式对话，SSE 单通道 run.event，id 为 seq */
    @PostMapping(value = "/api/v1/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody @Valid ChatStreamREQ req) {
        return runService.chatStream(req);
    }

    /** 运行分页，按会话过滤 */
    @GetMapping("/api/v1/run/page")
    public R<PageResult<RunVO>> page(SessionPageREQ req) {
        return R.ok(runService.page(req));
    }

    /** 运行详情 */
    @GetMapping("/api/v1/run/detail/{runId}")
    public R<RunVO> detail(@PathVariable String runId) {
        return R.ok(runService.detail(runId));
    }

    /** 事件回放，afterSeq 增量拉取 */
    @GetMapping("/api/v1/run/events/{runId}")
    public R<List<RunEventDTO>> events(@PathVariable String runId,
                                       @RequestParam(defaultValue = "0") long afterSeq) {
        return R.ok(runService.events(runId, afterSeq));
    }

    /** 中断运行 */
    @Idempotent(prefix = "run:abort")
    @PostMapping("/api/v1/run/abort/{runId}")
    public R<Void> abort(@PathVariable String runId) {
        runService.abort(runId);
        return R.ok();
    }
}
