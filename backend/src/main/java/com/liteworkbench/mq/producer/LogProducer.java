package com.liteworkbench.mq.producer;

import com.liteworkbench.config.RabbitConfig;
import com.liteworkbench.observability.LogPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 日志投递生产者：送 MQ 削峰，失败丢弃并限流告警 */
@Slf4j
@Component
public class LogProducer implements LogPublisher {

    private final RabbitTemplate rabbitTemplate;

    public LogProducer(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public void publish(LogMessage message) {
        try {
            rabbitTemplate.convertAndSend(RabbitConfig.LOG_EXCHANGE, RabbitConfig.LOG_ROUTING_KEY, message);
        } catch (Exception e) {
            // MQ 不可用降级为只落文件，不重试不阻塞；消息转换等非 AmqpException 同样兜住
            log.warn("[log.publish] 投递失败，降级为只落文件 {}", e.getMessage());
        }
    }
}
