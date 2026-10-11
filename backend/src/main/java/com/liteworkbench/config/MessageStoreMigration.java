package com.liteworkbench.config;

import cn.hutool.core.util.StrUtil;
import com.liteworkbench.common.TimeUtil;
import com.liteworkbench.domain.dto.MessageDocDTO;
import com.liteworkbench.domain.dto.MessageLegacyRow;
import com.liteworkbench.mapper.MessageIndexMapper;
import com.liteworkbench.mapper.WbMessageMigrationMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/** 旧版消息表一次性迁移：wb_message 行转投 Redisearch 消息索引后删表；失败保留原表下次启动重试 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageStoreMigration implements ApplicationRunner {

    /** 迁移批次大小 */
    private static final int BATCH_SIZE = 500;

    private final WbMessageMigrationMapper migrationMapper;
    private final MessageIndexMapper messageIndexMapper;

    @Override
    public void run(ApplicationArguments args) {
        try {
            if (!migrationMapper.tableExists()) {
                return;
            }
            long total = migrationMapper.countAll();
            if (total == 0) {
                migrationMapper.dropTable();
                log.info("[msg.migrate] 旧消息表为空，已删除");
                return;
            }
            messageIndexMapper.ensureIndex();
            long migrated = 0;
            for (long offset = 0; offset < total; offset += BATCH_SIZE) {
                List<MessageLegacyRow> rows = migrationMapper.selectBatch(offset, BATCH_SIZE);
                for (MessageLegacyRow row : rows) {
                    messageIndexMapper.add(new MessageDocDTO(
                            row.getMessageId(), StrUtil.nullToEmpty(row.getUserId()),
                            row.getSessionId(), row.getRunId(), row.getRoleCode(),
                            row.getContent(), Objects.requireNonNullElse(row.getTokenCount(), 0),
                            Objects.requireNonNullElse(TimeUtil.toMillis(row.getCreatedAt()), 0L)));
                }
                migrated += rows.size();
            }
            migrationMapper.dropTable();
            log.info("[msg.migrate] 旧消息表迁移完成 total={} migrated={}，wb_message 已删除", total, migrated);
        } catch (Exception e) {
            log.warn("[msg.migrate] 迁移未完成，保留 wb_message 下次启动重试 {} {}", e.getClass().getSimpleName(), e.getMessage());
        }
    }
}
