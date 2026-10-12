package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Manticore 检索参数：连接、超时与日志保留 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.manticore")
public class ManticoreProperties {

    /** HTTP JSON API 地址，容器网络内为 http://manticore:9308 */
    private String url = "http://localhost:9308";
    /** 连接超时毫秒 */
    private int connectTimeout = 3000;
    /** 读取超时毫秒 */
    private int readTimeout = 10000;
    /** 日志表保留天数，到期由定时任务删除 */
    private int logRetentionDays = 30;
}
