package com.liteworkbench.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.constant.EventTypeEnum;
import com.liteworkbench.domain.constant.PermConst;
import com.liteworkbench.domain.dto.TraceQueryREQ;
import com.liteworkbench.domain.entity.WbEventDO;
import com.liteworkbench.domain.entity.WbRunDO;
import com.liteworkbench.domain.vo.TraceRunVO;
import com.liteworkbench.domain.vo.TraceSpanVO;
import com.liteworkbench.domain.vo.UsageStatsVO;
import com.liteworkbench.mapper.WbEventMapper;
import com.liteworkbench.mapper.WbRunMapper;
import com.liteworkbench.mapper.WbTraceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 可观测性：把事件流还原成链路片段、把运行按父子组装成树、把账目字段汇总成用量归因 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TraceService {

    /** 链路片段扫描的事件上限：够长链路用，又不至于把整段历史读进内存 */
    private static final long SPAN_EVENT_LIMIT = 5000L;
    /** 用量归因默认回看天数 */
    private static final int DEFAULT_RANGE_DAYS = 30;
    /** 工具入参在片段里的展示上限 */
    private static final int ARG_PREVIEW_CHARS = 160;
    /** payload.phase 的开始值 */
    private static final String PHASE_START = "start";

    private final WbTraceMapper traceMapper;
    private final WbRunMapper runMapper;
    private final WbEventMapper eventMapper;

    /** 单次运行的链路片段，按开始时间正序；偏移量相对最早事件 */
    public List<TraceSpanVO> spans(String runId) {
        WbRunDO run = getOwned(runId);
        List<WbEventDO> events = eventMapper.selectByRunAfterSeq(runId, 0L, SPAN_EVENT_LIMIT);
        if (events.isEmpty()) {
            return List.of();
        }
        long origin = events.stream()
                .map(WbEventDO::getOccurredAt)
                .filter(Objects::nonNull)
                .mapToLong(TimeUtil::toMillis)
                .min()
                .orElse(System.currentTimeMillis());

        List<TraceSpanVO> spans = new ArrayList<>();
        Map<String, TraceSpanVO> openTools = new HashMap<>();
        TraceSpanVO openModel = null;
        int iteration = 0;
        for (WbEventDO event : events) {
            long at = TimeUtil.toMillis(event.getOccurredAt());
            String phase = phaseOf(event);
            if (isType(event, EventTypeEnum.MODEL_CALL)) {
                if (PHASE_START.equals(phase)) {
                    openModel = modelSpan(event, ++iteration, at - origin);
                } else if (Objects.nonNull(openModel)) {
                    finishModelSpan(openModel, event, at - origin);
                    spans.add(openModel);
                    openModel = null;
                }
            } else if (isType(event, EventTypeEnum.TOOL_CALL)) {
                String toolCallId = payloadValue(event, "toolCallId");
                if (PHASE_START.equals(phase)) {
                    openTools.put(toolCallId, toolSpan(event, toolCallId, at - origin));
                } else {
                    closeToolSpan(openTools, spans, toolCallId, at - origin, null);
                }
            } else if (isType(event, EventTypeEnum.TOOL_RESULT)) {
                // 起止事件与结果事件谁先到都收敛到同一处，中断时自然不会漏片段
                closeToolSpan(openTools, spans, payloadValue(event, "toolCallId"), at - origin, event);
            } else if (isType(event, EventTypeEnum.PLAN_UPDATED)) {
                spans.add(planSpan(event, at - origin));
            }
            // 其余事件不进瀑布图：正文增量是内容而非时间跨度
        }
        // 未收尾的模型段（运行中断）也要展示，否则瀑布图会看不到「卡在哪」
        if (Objects.nonNull(openModel)) {
            openModel.setStatus("pending");
            spans.add(openModel);
        }
        openTools.values().forEach(span -> {
            span.setStatus("pending");
            spans.add(span);
        });
        spans.sort(Comparator.comparingLong(TraceSpanVO::getOffsetMs));
        log.info("[trace.spans] runId={} events={} spans={}", run.getRunId(), events.size(), spans.size());
        return spans;
    }

    /** Run 树：以会话为单位组装，子运行挂在父运行下；入参是子运行也能回到整棵树 */
    public List<TraceRunVO> runTree(String sessionId) {
        String userId = LoginContext.userId();
        List<WbRunDO> runs = traceMapper.selectRunsBySession(userId, sessionId);
        if (runs.isEmpty()) {
            return List.of();
        }
        Map<String, Integer> eventCounts = eventCounts(sessionId);
        Map<String, TraceRunVO> nodes = new LinkedHashMap<>();
        runs.forEach(run -> nodes.put(run.getRunId(), toRunVO(run, eventCounts)));
        List<TraceRunVO> roots = new ArrayList<>();
        for (TraceRunVO node : nodes.values()) {
            TraceRunVO parent = StrUtil.isBlank(node.getParentRunId())
                    ? null : nodes.get(node.getParentRunId());
            if (Objects.isNull(parent)) {
                roots.add(node);
            } else {
                parent.getChildren().add(node);
            }
        }
        return roots;
    }

    /** 用量归因：自身用量默认只看自己；持有全局日志视角的角色可看全平台 */
    public UsageStatsVO usage(TraceQueryREQ req) {
        String userId = globalScope() ? null : LoginContext.userId();
        Long from = req.resolvedFrom(DEFAULT_RANGE_DAYS);
        Long to = req.getTo();
        UsageStatsVO stats = new UsageStatsVO();
        stats.setTotals(traceMapper.selectUsageTotals(userId, from, to));
        stats.setDaily(traceMapper.selectUsageDaily(userId, from, to));
        stats.setByModel(traceMapper.selectUsageByModel(userId, from, to));
        stats.setByState(traceMapper.selectUsageByState(userId, from, to));
        if (Objects.isNull(stats.getTotals())) {
            stats.setTotals(new UsageStatsVO.Totals());
        }
        return stats;
    }

    private Map<String, Integer> eventCounts(String sessionId) {
        Map<String, Integer> counts = new HashMap<>();
        for (Map<String, Object> row : traceMapper.selectEventCounts(sessionId)) {
            Object runId = row.get("runId");
            Object count = row.get("eventCount");
            if (Objects.nonNull(runId)) {
                counts.put(runId.toString(), Objects.isNull(count) ? 0 : Integer.parseInt(count.toString()));
            }
        }
        return counts;
    }

    private TraceRunVO toRunVO(WbRunDO run, Map<String, Integer> eventCounts) {
        TraceRunVO vo = new TraceRunVO();
        vo.setRunId(run.getRunId());
        vo.setParentRunId(run.getParentRunId());
        vo.setRelation(run.getRelation());
        vo.setPrompt(StrUtil.maxLength(StrUtil.nullToEmpty(run.getPrompt()), ARG_PREVIEW_CHARS));
        vo.setModelName(run.getModelName());
        vo.setStatus(run.getState());
        vo.setStopReason(run.getStopReason());
        vo.setIterCount(run.getIterCount());
        vo.setInputTokens(run.getInputTokens());
        vo.setOutputTokens(run.getOutputTokens());
        vo.setDurationMs(run.getDurationMs());
        vo.setStartedAt(TimeUtil.toMillis(Objects.nonNull(run.getStartedAt())
                ? run.getStartedAt() : run.getCreatedAt()));
        vo.setEventCount(eventCounts.getOrDefault(run.getRunId(), 0));
        return vo;
    }

    private TraceSpanVO modelSpan(WbEventDO event, int iteration, long offsetMs) {
        TraceSpanVO span = new TraceSpanVO();
        span.setSpanId("model-" + event.getSeq());
        span.setKind("model");
        span.setName("模型推理");
        span.setIteration(iteration);
        span.setOffsetMs(offsetMs);
        span.setStatus("pending");
        return span;
    }

    private void finishModelSpan(TraceSpanVO span, WbEventDO event, long endOffsetMs) {
        Map<String, Object> payload = payload(event);
        span.setDurationMs(Math.max(endOffsetMs - span.getOffsetMs(), 0L));
        span.setStatus("ok");
        span.setInputTokens(longValue(payload.get("inputTokens")));
        span.setOutputTokens(longValue(payload.get("outputTokens")));
        Object duration = payload.get("durationMs");
        if (Objects.nonNull(duration)) {
            span.setDurationMs(longValue(duration));
        }
    }

    private TraceSpanVO toolSpan(WbEventDO event, String toolCallId, long offsetMs) {
        Map<String, Object> payload = payload(event);
        TraceSpanVO span = new TraceSpanVO();
        span.setSpanId(StrUtil.blankToDefault(toolCallId, "tool-" + event.getSeq()));
        span.setKind("tool");
        span.setName(StrUtil.blankToDefault(String.valueOf(payload.get("toolName")), "工具"));
        span.setOffsetMs(offsetMs);
        span.setStatus("pending");
        Object arguments = payload.get("arguments");
        span.setArguments(StrUtil.maxLength(Objects.toString(arguments, StrUtil.EMPTY), ARG_PREVIEW_CHARS));
        return span;
    }

    /** 收口工具片段：无起止事件（中断）时由结果事件兜底，状态与输出一次性落定 */
    private void closeToolSpan(Map<String, TraceSpanVO> openTools, List<TraceSpanVO> spans,
                               String toolCallId, long endOffsetMs, WbEventDO resultEvent) {
        TraceSpanVO span = openTools.remove(toolCallId);
        if (Objects.isNull(span)) {
            return;
        }
        span.setDurationMs(Math.max(endOffsetMs - span.getOffsetMs(), 0L));
        span.setStatus("ok");
        if (Objects.nonNull(resultEvent)) {
            span.setOutputChars(StrUtil.length(payloadValue(resultEvent, "output")));
            if (Boolean.parseBoolean(payloadValue(resultEvent, "isError"))) {
                span.setStatus("error");
            }
        }
        spans.add(span);
    }

    private TraceSpanVO planSpan(WbEventDO event, long offsetMs) {
        TraceSpanVO span = new TraceSpanVO();
        span.setSpanId("plan-" + event.getSeq());
        span.setKind("plan");
        span.setName("计划更新");
        span.setOffsetMs(offsetMs);
        span.setDurationMs(0L);
        span.setStatus("ok");
        return span;
    }

    private String phaseOf(WbEventDO event) {
        return Objects.toString(payload(event).get("phase"), StrUtil.EMPTY);
    }

    private String payloadValue(WbEventDO event, String key) {
        Object value = payload(event).get(key);
        return Objects.isNull(value) ? StrUtil.EMPTY : value.toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> payload(WbEventDO event) {
        if (StrUtil.isBlank(event.getPayload())) {
            return Map.of();
        }
        try {
            return JSONUtil.toBean(event.getPayload(), Map.class);
        } catch (Exception e) {
            log.warn("[trace.payload] eventId={} 载荷解析失败 {}", event.getEventId(), e.getMessage());
            return Map.of();
        }
    }

    private Long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private boolean globalScope() {
        try {
            return StpUtil.hasPermission(PermConst.MONITOR_LOG_GLOBAL);
        } catch (Exception e) {
            return false;
        }
    }

    /** 归属校验：按 (运行, 用户) 查，查不到即 404，不泄露他人运行是否存在 */
    private WbRunDO getOwned(String runId) {
        WbRunDO run = runMapper.selectByIdAndUser(runId, LoginContext.userId());
        if (Objects.isNull(run)) {
            throw new BizException(ErrorCodeEnum.RUN_NOT_FOUND, runId);
        }
        return run;
    }

    private boolean isType(WbEventDO event, EventTypeEnum type) {
        return type.getCode().equals(event.getEventType());
    }
}
