package com.liteworkbench.domain.constant;

import java.util.Set;

/** 知识库静态常量：格式白名单、检索参数边界、Agent 工具名 */
public interface KbConst {

    /** Agent 检索工具名，与 wb_tool 种子一致 */
    String TOOL_NAME = "kb_search";

    /** 单个知识库文档数上限 */
    int MAX_DOC_COUNT = 200;

    /** 单文档大小上限 20MB */
    long MAX_DOC_SIZE = 20L * 1024 * 1024;

    /** 单文档提取正文上限（字符），超出截断 */
    int MAX_DOC_CHARS = 500_000;

    /** 文本直读的扩展名 */
    Set<String> TEXT_EXTENSIONS = Set.of("txt", "md", "csv", "json", "html", "xml", "log");

    /** 需要专门解析器的扩展名 */
    Set<String> PARSER_EXTENSIONS = Set.of("docx", "pdf");

    /** 单次检索返回片段上限（Agent 工具用） */
    int TOOL_SEARCH_LIMIT = 5;

    /** Agent 工具返回片段的拼接上限（字符） */
    int TOOL_OUTPUT_MAX = 6000;
}
