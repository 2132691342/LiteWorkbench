package com.liteworkbench.agent.spi;

/** 长期记忆端口：agent 只认此接口，容量闸门、去重与淘汰规则全在 service 侧 */
public interface MemoryPort {

    /** 写入或覆写一条记忆，返回面向模型的结果文本 */
    String write(String userId, String key, String value, String category, Integer weight);

    /** 检索当前用户记忆，返回带键与维度的拼装文本 */
    String search(String userId, String keyword, int limit);

    /** 取注入系统提示的记忆段落，无记忆时返回空串 */
    String promptBlock(String userId);
}
