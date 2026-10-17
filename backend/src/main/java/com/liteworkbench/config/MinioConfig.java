package com.liteworkbench.config;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** MinIO 客户端与桶初始化 */
@Slf4j
@Configuration
public class MinioConfig {

    /** 对象存储客户端 */
    @Bean
    public MinioClient minioClient(StorageProperties properties) {
        return MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
    }

    /** 启动时幂等创建桶，失败不阻断启动，健康面板标 DOWN */
    @Bean
    public ApplicationRunner minioBucketInitializer(MinioClient minioClient, StorageProperties properties) {
        return args -> {
            try {
                String bucket = properties.getBucketName();
                boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
                if (!exists) {
                    minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("[minio.init] 桶已创建 {}", bucket);
                }
            } catch (Exception e) {
                log.warn("[minio.init] 桶初始化失败，文件功能不可用 {}", e.getMessage());
            }
        };
    }
}
