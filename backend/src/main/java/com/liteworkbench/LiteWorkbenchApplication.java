package com.liteworkbench;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用启动入口 */
@Slf4j
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LiteWorkbenchApplication {

    public static void main(String[] args) {
        try {
            SpringApplication.run(LiteWorkbenchApplication.class, args);
        } catch (Throwable e) {
            // 启动失败必须显式退出：连接池、MQ 客户端等第三方组件会留下非守护线程，
            // 只靠主线程结束无法终止 JVM，容器会停在假 Up 状态直到被编排强杀
            log.error("[app.startup] 应用启动失败，进程退出: {}", e.getMessage());
            System.exit(1);
        }
    }
}
