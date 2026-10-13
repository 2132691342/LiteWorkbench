package com.liteworkbench.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 拓扑与消息转换：日志索引队列、知识库解析队列 */
@Configuration
public class RabbitConfig {

    public static final String LOG_EXCHANGE = "lw.log.exchange";
    public static final String LOG_QUEUE = "lw.log.index.queue";
    public static final String LOG_ROUTING_KEY = "lw.log.index";
    /** 日志队列死信交换机：毒消息（反序列化失败等）进死信队列，不无限重回阻塞后续日志 */
    public static final String LOG_DLX = "lw.log.dlx";
    public static final String LOG_DLQ = "lw.log.dead.queue";

    public static final String KB_EXCHANGE = "lw.kb.exchange";
    public static final String KB_PARSE_QUEUE = "lw.kb.parse.queue";
    public static final String KB_PARSE_ROUTING_KEY = "lw.kb.parse";

    @Bean
    public TopicExchange logExchange() {
        return new TopicExchange(LOG_EXCHANGE, true, false);
    }

    /** 日志索引队列：消费失败进死信队列（监听器内部异常已捕获，主要防消息体损坏的重试风暴） */
    @Bean
    public Queue logIndexQueue() {
        return QueueBuilder.durable(LOG_QUEUE)
                .withArgument("x-dead-letter-exchange", LOG_DLX)
                .build();
    }

    @Bean
    public TopicExchange logDeadLetterExchange() {
        return new TopicExchange(LOG_DLX, true, false);
    }

    @Bean
    public Queue logDeadLetterQueue() {
        return new Queue(LOG_DLQ, true);
    }

    @Bean
    public Binding logDeadLetterBinding(Queue logDeadLetterQueue, TopicExchange logDeadLetterExchange) {
        return BindingBuilder.bind(logDeadLetterQueue).to(logDeadLetterExchange).with(LOG_ROUTING_KEY);
    }

    @Bean
    public Binding logIndexBinding(Queue logIndexQueue, TopicExchange logExchange) {
        return BindingBuilder.bind(logIndexQueue).to(logExchange).with(LOG_ROUTING_KEY);
    }

    @Bean
    public TopicExchange kbExchange() {
        return new TopicExchange(KB_EXCHANGE, true, false);
    }

    /** 知识库解析队列：解析耗时且可重放，失败不重回队列，靠页面重解析触发 */
    @Bean
    public Queue kbParseQueue() {
        return new Queue(KB_PARSE_QUEUE, true);
    }

    @Bean
    public Binding kbParseBinding(Queue kbParseQueue, TopicExchange kbExchange) {
        return BindingBuilder.bind(kbParseQueue).to(kbExchange).with(KB_PARSE_ROUTING_KEY);
    }

    /** 结构化消息用 Jackson 序列化，记录类字段直接映射 */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
