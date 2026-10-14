package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 知识库参数：分块与解析限制 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.kb")
public class KbProperties {

    private Chunk chunk = new Chunk();

    @Data
    public static class Chunk {
        /** 单块目标字符数 */
        private int targetSize = 600;
        /** 相邻块重叠字符数 */
        private int overlap = 120;
    }
}
