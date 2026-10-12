package com.liteworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 应用启动入口 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class LiteWorkbenchApplication {

    public static void main(String[] args) {
        SpringApplication.run(LiteWorkbenchApplication.class, args);
    }
}
