package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import com.liteworkbench.agent.ModelFactory;
import com.liteworkbench.agent.model.ModelClient;
import com.liteworkbench.agent.model.ModelEventListener;
import com.liteworkbench.agent.model.ModelTypes;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.config.AgentProperties;
import com.liteworkbench.config.ExecutorConfig;
import com.liteworkbench.domain.constant.AppConst;
import com.liteworkbench.domain.constant.DocConst;
import com.liteworkbench.domain.dto.DocAssistREQ;
import com.liteworkbench.domain.entity.WbDocDO;
import com.liteworkbench.domain.entity.WbProviderDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/** AI 辅助写作：动作模板 + 选区 + 可选知识库片段 → 模型流式调用经 SSE 推送，不建 Run */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAssistService {

    /** 增量合帧间隔，毫秒 */
    private static final long FLUSH_INTERVAL_MS = 120L;

    private final ModelFactory modelFactory;
    private final DocService docService;
    private final KbDocService kbDocService;
    private final AgentProperties agentProperties;

    /** 辅助流借用运行编排池：一次辅助一个线程，阻塞在模型流上 */
    @Qualifier(ExecutorConfig.RUN_EXECUTOR)
    private final ExecutorService executor;

    /** 发起一次流式辅助：请求线程只做校验与派发，模型流在独立线程执行 */
    public void assistStream(DocAssistREQ req, DocAssistSink sink) {
        String userId = LoginContext.userId();
        String action = StrUtil.trimToEmpty(req.getAction());
        String template = DocConst.ASSIST_TEMPLATES.get(action);
        if (StrUtil.isBlank(template)) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的辅助动作");
        }
        String selection = resolveSelection(req, userId);
        String prompt = buildPrompt(template, action, selection,
                req.getInstruction(), resolveKbSnippet(req, userId, selection));
        executor.submit(() -> stream(userId, action, prompt, sink));
    }

    /** 模型流式执行：增量按间隔合帧推送，客户端断开即取消模型调用 */
    private void stream(String userId, String action, String prompt, DocAssistSink sink) {
        long start = System.currentTimeMillis();
        MDC.put(AppConst.MDC_USER_ID, userId);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        StringBuilder pending = new StringBuilder();
        try {
            sendSafely(sink, cancelled, "assist.start", Map.of("action", action));
            WbProviderDO provider = modelFactory.resolveDefaultProvider();
            String modelName = modelFactory.resolveModelName(provider, null);
            ModelClient client = modelFactory.client(provider.getProviderCode(), modelName);

            ModelTypes.Request request = new ModelTypes.Request();
            request.setMessages(List.of(ModelTypes.Msg.user(prompt)));
            request.setTemperature(agentProperties.getModel().getTemperature());
            request.setTimeoutSeconds(agentProperties.getModel().getStreamTimeoutSeconds());

            long[] lastFlush = {System.currentTimeMillis()};
            ModelTypes.StreamResult result = client.stream(request, new ModelEventListener() {
                @Override
                public void onText(String delta) {
                    if (cancelled.get()) {
                        return;
                    }
                    synchronized (pending) {
                        pending.append(delta);
                        long now = System.currentTimeMillis();
                        if (now - lastFlush[0] >= FLUSH_INTERVAL_MS) {
                            lastFlush[0] = now;
                            flush(sink, cancelled, pending);
                        }
                    }
                }
            }, cancelled::get);

            flush(sink, cancelled, pending);
            if (!cancelled.get()) {
                sendSafely(sink, cancelled, "assist.end", Map.of(
                        "action", action,
                        "text", StrUtil.nullToEmpty(result.getText()),
                        "durationMs", System.currentTimeMillis() - start));
            }
            log.info("[doc.assist] userId={} action={} model={} chars={} tokens={}/{} cost={}ms cancelled={}",
                    userId, action, modelName, result.getText().length(),
                    result.getUsage().getInputTokens(), result.getUsage().getOutputTokens(),
                    System.currentTimeMillis() - start, cancelled.get());
        } catch (Exception e) {
            log.warn("[doc.assist] userId={} action={} 失败 {}", userId, action, e.getMessage());
            sendSafely(sink, cancelled, "assist.error", Map.of(
                    "code", ErrorCodeEnum.MODEL_CALL_FAILED.getCode(),
                    "message", StrUtil.nullToEmpty(e.getMessage())));
        } finally {
            sink.complete();
            MDC.remove(AppConst.MDC_USER_ID);
        }
    }

    /** 冲刷累积增量：空缓冲不动 */
    private void flush(DocAssistSink sink, AtomicBoolean cancelled, StringBuilder pending) {
        String text;
        synchronized (pending) {
            if (pending.isEmpty()) {
                return;
            }
            text = pending.toString();
            pending.setLength(0);
        }
        sendSafely(sink, cancelled, "assist.delta", Map.of("text", text));
    }

    /** 推送失败（客户端断开）即置取消标志，模型客户端在下一检查点关闭连接；帧格式 type 与载荷同层 */
    private void sendSafely(DocAssistSink sink, AtomicBoolean cancelled, String eventType, Map<String, Object> payload) {
        JSONObject frame = new JSONObject(true);
        frame.set("type", eventType);
        payload.forEach(frame::set);
        try {
            sink.send(eventType, frame.toString());
        } catch (Exception e) {
            cancelled.set(true);
            log.warn("[doc.assist] 推送失败，取消模型调用 {}", e.getMessage());
        }
    }

    /** 选区为空时取整篇正文的前 N 字，保证动作始终有输入 */
    private String resolveSelection(DocAssistREQ req, String userId) {
        if (StrUtil.isNotBlank(req.getSelection())) {
            return StrUtil.sub(req.getSelection(), 0, DocConst.ASSIST_INPUT_MAX);
        }
        if (StrUtil.isBlank(req.getDocId())) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "请选中正文或打开一篇文档");
        }
        WbDocDO doc = docService.getOwned(req.getDocId(), userId);
        return StrUtil.sub(StrUtil.nullToEmpty(doc.getContent()), 0, DocConst.ASSIST_INPUT_MAX);
    }

    /** 引用知识库：以选区或补充要求作关键字，取前 N 段片段 */
    private String resolveKbSnippet(DocAssistREQ req, String userId, String selection) {
        if (StrUtil.isBlank(req.getKbId())) {
            return StrUtil.EMPTY;
        }
        String keyword = StrUtil.isNotBlank(req.getInstruction()) ? req.getInstruction()
                : StrUtil.sub(selection, 0, 60);
        String snippet = kbDocService.search(userId, keyword, req.getKbId(), DocConst.ASSIST_KB_TOP);
        return StrUtil.nullToEmpty(snippet);
    }

    /** 组装提示词：动作模板 + 知识库片段 + 正文 + 补充要求，段落边界用分隔标记隔离 */
    private String buildPrompt(String template, String action, String selection,
                               String instruction, String snippet) {
        StringBuilder prompt = new StringBuilder(template);
        if (StrUtil.isNotBlank(snippet)) {
            prompt.append("\n\n【参考资料】\n").append(snippet);
        }
        prompt.append("\n\n【正文】\n").append(selection);
        if (StrUtil.isNotBlank(instruction)) {
            // 翻译动作里 instruction 就是目标语言，其余动作作为补充要求
            prompt.append("translate".equals(action) ? "\n\n【目标语言】" : "\n\n【补充要求】\n").append(instruction);
        }
        return prompt.toString();
    }

}
