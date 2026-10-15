package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** 调用观测参数：SQL 与 HTTP 审计的开关、慢调用阈值与日志截断上限 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.observe")
public class ObserveProperties {

    private Sql sql = new Sql();
    private Http http = new Http();

    /** 记入日志即整值替换为 *** 的键名：忽略大小写与下划线后做包含匹配，SQL 入参与 HTTP 载荷共用 */
    private List<String> sensitiveKeys = List.of(
            "password", "apiKey", "apiKeyEnc", "secret", "token",
            "authorization", "cookie", "accessKey", "secretKey");

    /** SQL 审计参数 */
    @Data
    public static class Sql {

        /** 是否记录 SQL 执行 */
        private boolean enabled = true;
        /** 慢查询阈值，毫秒；耗时达到该值即升级为 warn */
        private long slowMs = 300L;
        /** 单条日志内 SQL 文本最大字符数 */
        private int maxSqlChars = 1200;
        /** 单条日志内入参摘要最大字符数 */
        private int maxParamChars = 800;
        /** 慢语句榜保留条数 */
        private int topSize = 10;
    }

    /** HTTP 审计参数 */
    @Data
    public static class Http {

        /** 是否记录 HTTP 请求与响应 */
        private boolean enabled = true;
        /** 慢请求阈值，毫秒；耗时达到该值即升级为 warn */
        private long slowMs = 1000L;
        /** 单条日志内请求体或响应体最大字符数 */
        private int maxBodyChars = 1000;
        /** 慢接口榜保留条数 */
        private int topSize = 10;
        /** 不审计的路径前缀：健康检查与前端静态资源 */
        private List<String> skipPrefixes = List.of("/actuator", "/assets", "/static", "/favicon", "/index.html");
        /** 记入请求日志的请求头，其余一律不落盘 */
        private List<String> logHeaders = List.of("content-type", "accept-language", "referer", "x-forwarded-for");
    }
}
