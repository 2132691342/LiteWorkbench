package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 对象存储参数：MinIO 连接与桶 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.storage")
public class StorageProperties {

    /** MinIO 端点 */
    private String endpoint = "http://localhost:9000";
    /** Access Key */
    private String accessKey = "minioadmin";
    /** Secret Key */
    private String secretKey = "minioadminpublic";
    /** 桶名，启动时幂等创建 */
    private String bucketName = "liteworkbench";
}
