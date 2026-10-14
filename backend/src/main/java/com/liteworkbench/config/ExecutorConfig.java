package com.liteworkbench.config;

import cn.hutool.core.thread.ThreadUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 全局线程资源：一处声明、按用途分池、统一命名与优雅关闭，禁止业务类裸建线程池 */
@Slf4j
@Configuration
public class ExecutorConfig {

    /** 运行编排：SSE 主链路，拒绝时提交方降速而非丢任务 */
    public static final String RUN_EXECUTOR = "lwRunExecutor";
    /** 工具执行：快速失败让内核收敛 */
    public static final String TOOL_EXECUTOR = "lwToolExecutor";
    /** 观测投递与低频后台任务：不得反压业务链路 */
    public static final String EVENT_EXECUTOR = "lwEventExecutor";
    /** 组件健康并发探测：逐项带超时，探测不可反压调用方 */
    public static final String PROBE_EXECUTOR = "lwProbeExecutor";
    /** 心跳、超时巡检、日志清理等短周期内部任务 */
    public static final String SCHEDULE_EXECUTOR = "lwScheduleExecutor";

    /** 小容器核数不稳定，池规模按核数推导并留出下限 */
    private static final int CORES = Math.max(2, Runtime.getRuntime().availableProcessors());
    /** 停机等待窗口，秒；超过后强制中断，避免容器 stop 挂住 10s 以上 */
    private static final int AWAIT_SECONDS = 10;
    /** 非核心线程空闲回收时间，秒 */
    private static final long KEEP_ALIVE_SECONDS = 60L;

    @Bean(name = RUN_EXECUTOR, destroyMethod = "destroy")
    public ExecutorService runExecutor() {
        return new GracefulExecutor("lw-run-", CORES * 2, CORES * 4, 256,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = TOOL_EXECUTOR, destroyMethod = "destroy")
    public ExecutorService toolExecutor() {
        return new GracefulExecutor("lw-tool-", CORES * 2, CORES * 4, 512,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @Bean(name = EVENT_EXECUTOR, destroyMethod = "destroy")
    public ExecutorService eventExecutor() {
        // 观测链路满则丢弃：拒绝时必须留下计数，否则丢弃不可见
        return new GracefulExecutor("lw-event-", 4, 4, 2048,
                (task, pool) -> log.warn("[executor.event] 事件投递队列已满，丢弃任务 pool={}", pool));
    }

    @Bean(name = PROBE_EXECUTOR, destroyMethod = "destroy")
    public ExecutorService probeExecutor() {
        return new GracefulExecutor("lw-probe-", CORES, CORES * 2, 64,
                new ThreadPoolExecutor.CallerRunsPolicy());
    }

    @Bean(name = SCHEDULE_EXECUTOR)
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

    /**
     * 统一收尾的三段式池：Bean 类型即 ExecutorService，注入点无需感知实现。
     * 关闭顺序必须是「停收 → 等在途 → 超时中断」，否则在途运行会被直接掐断。
     */
    private static final class GracefulExecutor extends ThreadPoolExecutor {

        private final String prefix;

        private GracefulExecutor(String prefix, int corePoolSize, int maxPoolSize, int queueCapacity,
                                 RejectedExecutionHandler rejectedHandler) {
            super(corePoolSize, maxPoolSize, KEEP_ALIVE_SECONDS, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(queueCapacity),
                    // 守护线程：启动失败等异常路径下 JVM 必须能退出，非守护池线程会把容器挂成假 Up
                    ThreadUtil.newNamedThreadFactory(prefix, true), rejectedHandler);
            this.prefix = prefix;
        }

        /** Spring 销毁回调：由 @Bean(destroyMethod = "destroy") 指定，只调用一次 */
        public void destroy() {
            shutdown();
            try {
                if (!awaitTermination(AWAIT_SECONDS, TimeUnit.SECONDS)) {
                    log.warn("[executor.shutdown] 等待在途任务超时，强制中断 prefix={} pending={}",
                            prefix, getQueue().size());
                    shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                shutdownNow();
            }
        }
    }
}
