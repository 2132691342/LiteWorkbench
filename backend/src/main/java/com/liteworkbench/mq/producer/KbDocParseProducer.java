package com.liteworkbench.mq.producer;

import com.liteworkbench.domain.constant.MqConst;
import com.liteworkbench.domain.dto.KbDocParseDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/** 知识库解析任务生产者：解析不占请求线程，投递失败直接置文档失败由页面重放 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbDocParseProducer {

    private final RabbitTemplate rabbitTemplate;

    /** 派发解析任务；投递失败抛出，由上传链路把文档置为失败 */
    public void publish(KbDocParseDTO task) {
        try {
            rabbitTemplate.convertAndSend(MqConst.KB_EXCHANGE, MqConst.KB_PARSE_ROUTING_KEY, task);
        } catch (AmqpException e) {
            log.warn("[kb.parse.publish] 投递失败 docId={} {}", task.docId(), e.getMessage());
            throw e;
        }
    }
}
