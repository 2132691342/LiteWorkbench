package com.liteworkbench.config;

import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Queue;
import org.springframework.stereotype.Component;
import org.springframework.context.SmartLifecycle;

/** 队列拓扑对账：启动时以代码声明为准修复既有队列参数，避免历史队列缺死信配置导致声明 406 后日志链路静默断掉 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RabbitTopologyGuard implements SmartLifecycle {

    /** AMQP 0-9-1 的 declare-ok 不回传队列参数，故用"按声明重试 + 406 识别"判断参数是否一致 */
    private static final String PRECONDITION_FAILED = "PRECONDITION_FAILED";

    private final AmqpAdmin amqpAdmin;
    private final List<Queue> queues;

    private volatile boolean running;

    /** 早于监听容器（phase = Integer.MAX_VALUE - 100）执行，修好拓扑再接入消费 */
    @Override
    public int getPhase() {
        return 0;
    }

    /** 校验队列与死信配置，修补漂移 */
    @Override
    public void start() {
        long begin = System.currentTimeMillis();
        int repaired = 0;
        for (Queue queue : queues) {
            if (repair(queue)) {
                repaired++;
            }
        }
        running = true;
        log.info("[mq.topology] 队列对账完成 queueCount={} repaired={} cost={}ms",
                queues.size(), repaired, System.currentTimeMillis() - begin);
    }

    /** 按声明重试：参数一致即幂等成功；报参数不等则删掉按代码声明重建，绑定由 initialize() 补齐 */
    private boolean repair(Queue queue) {
        try {
            amqpAdmin.declareQueue(queue);
            return false;
        } catch (Exception e) {
            String reason = rootMessage(e);
            if (!reason.contains(PRECONDITION_FAILED)) {
                log.warn("[mq.topology] 队列声明失败非参数冲突，跳过修复 queue={} reason={}", queue.getName(), reason);
                return false;
            }
            int pending = pendingCount(queue.getName());
            amqpAdmin.deleteQueue(queue.getName());
            amqpAdmin.initialize();
            log.warn("[mq.topology] 队列参数不一致已重建 queue={} declared={} dropped={}",
                    queue.getName(), queue.getArguments(), pending);
            return true;
        }
    }

    private String rootMessage(Throwable error) {
        Throwable current = error;
        while (Objects.nonNull(current.getCause())) {
            current = current.getCause();
        }
        return Objects.isNull(current.getMessage()) ? "" : current.getMessage();
    }

    private int pendingCount(String queueName) {
        try {
            Object count = amqpAdmin.getQueueProperties(queueName).get("QUEUE_MESSAGE_COUNT");
            return Objects.isNull(count) ? 0 : Integer.parseInt(String.valueOf(count));
        } catch (Exception e) {
            return 0;
        }
    }

    /** 置停 */
    @Override
    public void stop() {
        running = false;
    }

    /** 生命周期状态 */
    @Override
    public boolean isRunning() {
        return running;
    }
}
