package com.liteworkbench.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ThreadPoolExecutor;

/** 全局线程资源：一处声明、按用途分池、统一命名与优雅关闭，禁止业务类裸建线程池 */
@Slf4j
@Configuration
@EnableAsync
public class ExecutorConfig {

    /** 运行编排：CSS SSE 主链路，拒绝时提交方降速而非丢任务 */
    public static final String RUN_EXECUTOR = "lwRunExecutor";
    /** 工具执行：快速失败让内核收敛 */
    public static final String TOOL_EXECUTOR = "lwToolExecutor";
    /** 事件扇出与观测投递：不得反压业务链路 */
    public static final String EVENT_EXECUTOR = "lwEventExecutor";
    /** 知识库解析入库：宁可慢也不丢用户以为已上传的文档 */
    public static final String INDEX_EXECUTOR = "lwIndexExecutor";
    /** 心跳、超时巡检、日志清理等短周期内部任务 */
    public static final String SCHEDULE_EXECUTOR = "lwScheduleExecutor";

    /** 小容器核数不稳定，池规模按核数推导并留出下限 */
    private static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors());
    /** 停机等待窗口：超过后强制中断，避免容器 stop 挂住 10s 以上 */
    private static final int AWAIT_SECONDS = 10;

    @Bean(RUN_EXECUTOR)
    public ThreadPoolTaskExecutor runExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORES * 2);
        executor.setMaxPoolSize(CORES * 4);
        executor.setQueueCapacity(256);
        executor.setThreadNamePrefix("lw-run-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        applyShutdownPolicy(executor);
        return executor;
    }

    @Bean(TOOL_EXECUTOR)
    public ThreadPoolTaskExecutor toolExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORES * 2);
        executor.setMaxPoolSize(CORES * 4);
        executor.setQueueCapacity(512);
        executor.setThreadNamePrefix("lw-tool-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        applyShutdownPolicy(executor);
        return executor;
    }

    @Bean(EVENT_EXECUTOR)
    public ThreadPoolTaskExecutor eventExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(2048);
        executor.setThreadNamePrefix("lw-event-");
        // 观测链路满则丢弃：拒绝时必须留下计数，否则丢弃不可见
        executor.setRejectedExecutionHandler((task, pool) ->
                log.warn("[executor.event] 事件投递队列已满，丢弃任务 pool={}", pool));
        applyShutdownPolicy(executor);
        return executor;
    }

    @Bean(INDEX_EXECUTOR)
    public ThreadPoolTaskExecutor indexExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(CORES);
        executor.setMaxPoolSize(CORES * 2);
        executor.setQueueCapacity(128);
        executor.setThreadNamePrefix("lw-index-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        applyShutdownPolicy(executor);
        return executor;
    }

    @Bean(SCHEDULE_EXECUTOR)
    public ThreadPoolTaskScheduler scheduleExecutor() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("lw-schedule-");
        scheduler.setRemoveOnCancelPolicy(true);
        scheduler.setErrorHandler(t -> log.warn("[executor.schedule] 周期任务异常 {}", t.getMessage()));
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(AWAIT_SECONDS);
        return scheduler;
    }

    /** 统一的三段式收尾：先停收新任务、等待在途完成、超时后强制中断 */
    private void applyShutdownPolicy(ThreadPoolTaskExecutor executor) {
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(AWAIT_SECONDS);
    }
}
