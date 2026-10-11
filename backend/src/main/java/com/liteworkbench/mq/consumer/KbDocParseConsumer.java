package com.liteworkbench.mq.consumer;

import com.liteworkbench.domain.dto.KbDocParseDTO;
import com.liteworkbench.service.KbDocService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/** 知识库解析消费者：提文本 → 分块 → 写索引 → 更新状态；失败由 KbDocService 内部落 failed，不重回队列 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KbDocParseConsumer {

    private final KbDocService kbDocService;

    /** 消费解析任务 */
    @RabbitListener(queues = "#{kbParseQueue.name}")
    public void onMessage(KbDocParseDTO task) {
        try {
            kbDocService.parse(task);
        } catch (Exception e) {
            log.warn("[kb.parse.consume] 消费失败 docId={} {}", task.docId(), e.getMessage());
        }
    }
}
