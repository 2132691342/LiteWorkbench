package com.liteworkbench.domain.constant;

/** RabbitMQ 拓扑常量：交换机、队列与路由键的唯一来源，声明方与投递方共用 */
public interface MqConst {

    /** 日志索引交换机 */
    String LOG_EXCHANGE = "lw.log.exchange";
    /** 日志索引队列 */
    String LOG_QUEUE = "lw.log.index.queue";
    /** 日志索引路由键 */
    String LOG_ROUTING_KEY = "lw.log.index";
    /** 日志死信交换机：毒消息（反序列化失败等）转此，不无限重回阻塞后续日志 */
    String LOG_DLX = "lw.log.dlx";
    /** 日志死信队列 */
    String LOG_DLQ = "lw.log.dead.queue";

    /** 知识库交换机 */
    String KB_EXCHANGE = "lw.kb.exchange";
    /** 知识库解析队列 */
    String KB_PARSE_QUEUE = "lw.kb.parse.queue";
    /** 知识库解析路由键 */
    String KB_PARSE_ROUTING_KEY = "lw.kb.parse";
}
