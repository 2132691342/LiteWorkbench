package com.liteworkbench.config;

import com.liteworkbench.mapper.LogSearchMapper;
import com.liteworkbench.mq.producer.LogProducer;
import com.liteworkbench.observability.LogPublisher;
import com.liteworkbench.observability.ManticoreLogAppender;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 日志汇聚接线：Appender → LogProducer(MQ)，启动时幂等建表 */
@Slf4j
@Configuration
public class LogObservabilityConfig {

    /** 把生产者注入 Logback Appender 的静态出口 */
    @Bean
    public LogPublisher logPublisherBridge(LogProducer producer) {
        ManticoreLogAppender.setPublisher(producer);
        return producer;
    }

    /** 启动幂等创建 Manticore 日志表，失败不阻断启动 */
    @Bean
    public ApplicationRunner logIndexInitializer(LogSearchMapper logSearchMapper) {
        return args -> logSearchMapper.ensureTable();
    }
}
