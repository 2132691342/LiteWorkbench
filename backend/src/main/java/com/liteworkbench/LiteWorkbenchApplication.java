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
            // 启动失败必须显式退出：第三方组件的非守护线程会让容器停在假 Up 状态
            log.error("[app.startup] 应用启动失败，进程退出: {}", e.getMessage());
            System.exit(1);
        }
    }
}
