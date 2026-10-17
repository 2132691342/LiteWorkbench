package com.liteworkbench.config;

import com.liteworkbench.mapper.DocIndexMapper;
import com.liteworkbench.mapper.KbChunkIndexMapper;
import com.liteworkbench.mapper.LogSearchMapper;
import com.liteworkbench.mapper.MessageIndexMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Manticore 检索表接线：消息、知识块、创作文档与日志表启动时幂等创建，失败不阻断启动 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ManticoreIndexConfig {

    private final MessageIndexMapper messageIndexMapper;
    private final KbChunkIndexMapper kbChunkIndexMapper;
    private final DocIndexMapper docIndexMapper;
    private final LogSearchMapper logSearchMapper;

    /** 启动即建日志与知识库索引表 */
    @Bean
    public ApplicationRunner searchIndexInitializer() {
        return args -> {
            boolean msg = messageIndexMapper.ensureTable();
            boolean kb = kbChunkIndexMapper.ensureTable();
            boolean doc = docIndexMapper.ensureTable();
            boolean logTable = logSearchMapper.ensureTable(false);
            log.info("[index.init] msg={} kbchunk={} doc={} log={}", msg, kb, doc, logTable);
        };
    }
}
