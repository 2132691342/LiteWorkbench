package com.liteworkbench.mq.consumer;

import com.liteworkbench.mapper.LogSearchMapper;
import com.liteworkbench.observability.LogPublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 日志索引消费者：批量写 Redisearch，失败仅告警不重回队列 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogConsumer {

    private final LogSearchMapper logSearchMapper;

    /** 消费日志文档，写入索引 */
    @RabbitListener(queues = "#{logIndexQueue.name}")
    public void onMessage(LogPublisher.LogMessage message) {
        try {
            logSearchMapper.add(message);
        } catch (Exception e) {
            log.warn("[log.consume] 索引写入失败 logId={} {}", message.logId(), e.getMessage());
        }
    }
}
