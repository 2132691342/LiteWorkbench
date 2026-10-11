package com.liteworkbench.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** RabbitMQ 拓扑与消息转换：日志索引队列 */
@Configuration
public class RabbitConfig {

    public static final String LOG_EXCHANGE = "lw.log.exchange";
    public static final String LOG_QUEUE = "lw.log.index.queue";
    public static final String LOG_ROUTING_KEY = "lw.log.index";

    @Bean
    public TopicExchange logExchange() {
        return new TopicExchange(LOG_EXCHANGE, true, false);
    }

    @Bean
    public Queue logIndexQueue() {
        return new Queue(LOG_QUEUE, true);
    }

    @Bean
    public Binding logIndexBinding(Queue logIndexQueue, TopicExchange logExchange) {
        return BindingBuilder.bind(logIndexQueue).to(logExchange).with(LOG_ROUTING_KEY);
    }

    /** 结构化消息用 Jackson 序列化，记录类字段直接映射 */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
