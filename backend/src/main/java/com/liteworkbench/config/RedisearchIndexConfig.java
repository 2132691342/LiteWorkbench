package com.liteworkbench.config;

import com.liteworkbench.mapper.DocIndexMapper;
import com.liteworkbench.mapper.KbChunkIndexMapper;
import com.liteworkbench.mapper.MessageIndexMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Redisearch 索引接线：消息、知识块与创作文档索引启动时幂等创建，失败不阻断启动 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class RedisearchIndexConfig {

    private final MessageIndexMapper messageIndexMapper;
    private final KbChunkIndexMapper kbChunkIndexMapper;
    private final DocIndexMapper docIndexMapper;

    @Bean
    public ApplicationRunner searchIndexInitializer() {
        return args -> {
            boolean msg = messageIndexMapper.ensureIndex();
            boolean kb = kbChunkIndexMapper.ensureIndex();
            boolean doc = docIndexMapper.ensureIndex();
            log.info("[index.init] msg={} kbchunk={} doc={}", msg, kb, doc);
        };
    }
}
