package com.liteworkbench.service;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.spi.KbSearchPort;
import com.liteworkbench.common.BizException;
import com.liteworkbench.common.ErrorCodeEnum;
import com.liteworkbench.domain.constant.RunRefTypeEnum;
import com.liteworkbench.domain.dto.RunRefREQ;
import com.liteworkbench.domain.entity.WbDocDO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/** 运行期引用注入：把输入框 @ 选中的知识库 / 文件 / 创作文档解析成系统提示里的上下文块 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RunRefService {

    /** 单块正文上限，字符数 */
    private static final int REF_MAX_CHARS = 8000;
    /** 单轮全部引用合计上限，字符数；超出部分丢弃并记日志 */
    private static final int TOTAL_MAX_CHARS = 24000;
    /** 知识库引用预取分块数 */
    private static final int KB_REF_CHUNKS = 6;

    private final KbSearchPort kbSearchPort;
    private final FileService fileService;
    private final DocService docService;

    /** 组装引用上下文块；无有效引用返回空串 */
    public String buildBlock(String userId, String userInput, List<RunRefREQ> refs) {
        if (CollUtil.isEmpty(refs)) {
            return "";
        }
        StringBuilder block = new StringBuilder();
        for (RunRefREQ ref : refs) {
            if (block.length() >= TOTAL_MAX_CHARS) {
                log.warn("[run.ref] 引用上下文超出上限已截断 userId={} refs={}", userId, refs.size());
                break;
            }
            RunRefTypeEnum type = RunRefTypeEnum.ofCode(ref.getType());
            if (Objects.isNull(type)) {
                throw new BizException(ErrorCodeEnum.PARAM_INVALID, "未知引用类型：" + ref.getType());
            }
            String section = switch (type) {
                case KB -> knowledge(userId, userInput, ref);
                case FILE -> file(userId, ref);
                case DOC -> document(userId, ref);
            };
            if (StrUtil.isNotBlank(section)) {
                block.append("\n\n").append(section);
            }
        }
        if (block.isEmpty()) {
            return "";
        }
        log.info("[run.ref] userId={} refs={} chars={}", userId, refs.size(), block.length());
        return block.toString();
    }

    /** 知识库引用：按当前问题预取相关分块，模型仍可用 kb_search 指定 kbId 深入检索 */
    private String knowledge(String userId, String userInput, RunRefREQ ref) {
        String snippets = kbSearchPort.search(userId, userInput, ref.getId(), KB_REF_CHUNKS);
        return StrUtil.format("[引用知识库：{}] 以下片段按当前问题预取，优先据此作答并标出来源；"
                + "需要更多细节时用 kb_search 指定 kbId={} 继续检索。\n{}", label(ref), ref.getId(), snippets);
    }

    /** 文件引用：文本类直接给正文，二进制给出明确提示，避免模型凭空猜测内容 */
    private String file(String userId, RunRefREQ ref) {
        String text = fileService.readText(userId, ref.getId(), REF_MAX_CHARS);
        if (StrUtil.isBlank(text)) {
            return StrUtil.format("[引用文件：{}] 该文件不是文本格式或内容为空，无法直接读取正文；"
                    + "需要其内容时请说明用途，让用户转成文本文件后重试。", label(ref));
        }
        return StrUtil.format("[引用文件：{}]\n{}", label(ref), text);
    }

    /** 创作文档引用：优先注入纯文本正文 */
    private String document(String userId, RunRefREQ ref) {
        WbDocDO doc = docService.getOwned(ref.getId(), userId);
        String content = StrUtil.blankToDefault(doc.getContentText(), doc.getContent());
        return StrUtil.format("[引用创作文档：{}]\n{}", label(ref), StrUtil.maxLength(StrUtil.nullToEmpty(content), REF_MAX_CHARS));
    }

    private static String label(RunRefREQ ref) {
        return StrUtil.blankToDefault(ref.getLabel(), ref.getId());
    }
}
