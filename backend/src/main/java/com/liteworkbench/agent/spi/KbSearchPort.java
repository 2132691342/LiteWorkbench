package com.liteworkbench.agent.spi;

/** 知识库检索端口：agent 只认此接口，由 service 侧适配实现 */
public interface KbSearchPort {

    /** 检索当前用户知识库，返回带来源标注的片段拼装文本；kbId 为空时跨库检索 */
    String search(String userId, String keyword, String kbId, int limit);
}
