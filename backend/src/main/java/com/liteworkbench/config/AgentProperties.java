package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 装配参数：模型、循环、上下文与技能 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.agent")
public class AgentProperties {

    /** 模型参数 */
    private Model model = new Model();
    /** 推理循环参数 */
    private Loop loop = new Loop();
    /** 历史消息参数 */
    private History history = new History();
    /** 技能注入开关 */
    private Skill skill = new Skill();

    @Data
    public static class Model {
        /** 兜底模型名，会话与供应方未指定时使用 */
        private String defaultName = "qwen-plus";
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 上下文窗口 token 数，超过即压缩 */
        private int contextWindow = 32000;
        /** 预留给模型输出的 token 数 */
        private int reserveTokens = 4096;
        /** 流式调用超时，秒 */
        private int streamTimeoutSeconds = 180;
        /** 同步调用超时，秒 */
        private int syncTimeoutSeconds = 120;
        /** 连接超时，秒 */
        private int connectTimeoutSeconds = 30;
    }

    @Data
    public static class Loop {
        /** 单轮最大迭代次数，超出发 run.exceed */
        private int maxIters = 20;
        /** 同一工具同一参数的重复调用护栏 */
        private int repeatGuard = 3;
        /** 单次工具执行超时，秒 */
        private long toolTimeoutSeconds = 60L;
    }

    @Data
    public static class History {
        /** 组装上下文时读取的历史消息条数上限 */
        private int limit = 40;
    }

    @Data
    public static class Skill {
        /** 是否注入技能 */
        private boolean enabled = true;
    }
}
