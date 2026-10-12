package com.liteworkbench.observability;

import com.liteworkbench.config.ManticoreProperties;
import com.liteworkbench.mapper.LogSearchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 日志保留期清理：Manticore 无文档级 TTL，每日定时删除过期日志文档 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LogRetentionJob {

    /** 一天的毫秒数 */
    private static final long DAY_MS = 24L * 3600 * 1000;

    private final LogSearchMapper logSearchMapper;
    private final ManticoreProperties manticoreProperties;

    /** 每日 03:30 执行，与文件日志滚动同周期 */
    @Scheduled(cron = "0 30 3 * * ?")
    public void cleanExpiredLogs() {
        long cutoff = System.currentTimeMillis() - manticoreProperties.getLogRetentionDays() * DAY_MS;
        long removed = logSearchMapper.deleteBefore(cutoff);
        if (removed > 0) {
            log.info("[log.retention] 清理过期日志 cutoff={} removed={}", cutoff, removed);
        }
    }
}
