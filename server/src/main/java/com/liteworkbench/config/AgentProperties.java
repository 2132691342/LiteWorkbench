package com.liteworkbench.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Agent 装配参数：模型、迭代、技能 */
@Data
@ConfigurationProperties(prefix = "liteworkbench.agent")
public class AgentProperties {

    /** 默认供应方编码 */
    private String provider = "openai";
    /** 模型参数 */
    private Model model = new Model();
    /** ReAct 循环参数 */
    private Loop loop = new Loop();
    /** 技能注入开关 */
    private Skill skill = new Skill();

    @Data
    public static class Model {
        /** 兜底模型名，会话与供应方未指定时使用 */
        private String defaultName = "qwen-plus";
        /** 采样温度 */
        private Double temperature = 0.3;
        /** 流式开关，固定 true */
        private boolean stream = true;
    }

    @Data
    public static class Loop {
        /** 单轮最大迭代次数，超出发 run.exceed */
        private int maxIters = 20;
    }

    @Data
    public static class Skill {
        /** 是否注入技能 */
        private boolean enabled = true;
    }
}
