package com.liteworkbench.domain.constant;

import java.util.Set;

/** 长期记忆静态常量：维度白名单、容量边界与 Agent 工具名 */
public interface MemoryConst {

    /** Agent 记忆写入工具名，与 wb_tool 种子一致 */
    String TOOL_WRITE = "memory_write";
    /** Agent 记忆检索工具名，与 wb_tool 种子一致 */
    String TOOL_SEARCH = "memory_search";

    /** 单用户记忆条数上限，超出后按权重淘汰 */
    int MAX_PER_USER = 200;
    /** 单条 key 长度上限 */
    int MAX_KEY_LENGTH = 128;
    /** 单条 value 长度上限 */
    int MAX_VALUE_LENGTH = 2000;
    /** 单次检索返回条数上限 */
    int SEARCH_LIMIT = 5;
    /** 注入系统提示的记忆条数：只取权重最高的少量，避免挤占上下文 */
    int PROMPT_LIMIT = 5;
    /** 注入系统提示的总字符上限 */
    int PROMPT_MAX_CHARS = 2000;
    /** 工具返回的总字符上限 */
    int TOOL_OUTPUT_MAX = 4000;

    /** 记忆维度白名单 */
    Set<String> CATEGORIES = Set.of("fact", "preference", "constraint", "profile");
}
