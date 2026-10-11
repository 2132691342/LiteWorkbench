package com.liteworkbench.domain.constant;

import java.util.Map;

/** 创作台静态常量：正文边界、版本保留条数、AI 辅助动作模板 */
public interface DocConst {

    /** 单文档正文上限（字符） */
    int CONTENT_MAX = 200_000;

    /** 单文档保留的版本条数，超出按版本号裁剪 */
    int VERSION_KEEP = 30;

    /** AI 辅助单次引用的知识库片段数 */
    int ASSIST_KB_TOP = 3;

    /** AI 辅助送入模型的选区与正文上限（字符），超出截断尾部 */
    int ASSIST_INPUT_MAX = 12_000;

    /** 内置用户：提示词内置模板的归属标识 */
    String BUILTIN_USER_ID = "builtin";

    /** AI 辅助动作模板：动作 -> 模型指令 */
    Map<String, String> ASSIST_TEMPLATES = Map.of(
            "continue", "你是中文写作助手。请基于已有正文，延续其风格与逻辑继续写作，只输出续写内容，不要复述原文。",
            "polish", "你是中文写作助手。请润色下面这段文字，保持原意，改善表达流畅度与用词，只输出润色后的文本。",
            "expand", "你是中文写作助手。请扩写下面这段文字，补充论据、细节与例证，保持主题一致，只输出扩写后的文本。",
            "summary", "你是中文写作助手。请为下面这段文字生成简明摘要，保留关键结论，只输出摘要文本。",
            "outline", "你是中文写作助手。请围绕主题产出结构化大纲，使用 Markdown 多级列表，只输出大纲。",
            "translate", "你是翻译助手。请把下面这段文字翻译为指定目标语言，保持术语一致，只输出译文。");
}
