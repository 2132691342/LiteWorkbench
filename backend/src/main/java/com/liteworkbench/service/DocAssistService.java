package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.ModelCaller;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.common.LoginContext;
import com.liteworkbench.domain.constant.DocConst;
import com.liteworkbench.domain.dto.DocAssistREQ;
import com.liteworkbench.domain.entity.WbDocDO;
import com.liteworkbench.domain.vo.DocAssistVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/** AI 辅助写作：动作模板 + 选区 + 可选知识库片段 → 同步调用模型返回纯文本，不建 Run */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocAssistService {

    private final ModelCaller modelCaller;
    private final DocService docService;
    private final KbDocService kbDocService;

    /** 执行一次辅助动作，返回模型产出的纯文本 */
    public DocAssistVO assist(DocAssistREQ req) {
        String userId = LoginContext.userId();
        String action = StrUtil.trimToEmpty(req.getAction());
        String template = DocConst.ASSIST_TEMPLATES.get(action);
        if (StrUtil.isBlank(template)) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "不支持的辅助动作");
        }
        long start = System.currentTimeMillis();
        String selection = resolveSelection(req, userId);
        String prompt = buildPrompt(template, action, selection, req.getInstruction(), resolveKbSnippet(req, userId, selection));

        String text = modelCaller.complete(prompt);
        DocAssistVO vo = new DocAssistVO();
        vo.setAction(action);
        vo.setText(text);
        vo.setDurationMs(System.currentTimeMillis() - start);
        log.info("[doc.assist] userId={} action={} chars={} cost={}ms",
                userId, action, text.length(), vo.getDurationMs());
        return vo;
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
