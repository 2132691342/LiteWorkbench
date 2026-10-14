package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 内置运行时参数：Python 探测与执行 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.runtime")
public class RuntimeProperties {

    /** 运行时数据根目录，Python 工作目录与内置解释器都挂在它下面 */
    private String dataDir = "./data";
    private Python python = new Python();

    @Data
    public static class Python {
        /** 是否启用 Python 工具 */
        private boolean enabled = true;
        /** 指定解释器绝对路径，空则按 数据目录 → PATH 顺序探测 */
        private String path = "";
        /** 默认执行超时秒数，硬上限 300 */
        private int timeoutSeconds = 120;
    }
}
