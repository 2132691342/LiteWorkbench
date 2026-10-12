package com.liteworkbench.agent.tool;

import com.liteworkbench.domain.constant.ToolRiskEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 工具声明：标注在返回 String 的公开方法上，由 ToolRegistry 扫描注册 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface AgentTool {

    /** 调用名，模型可见，全局唯一 */
    String name();

    /** 面向模型的用途说明，越具体越好 */
    String description() default "";

    /** 风险等级 */
    ToolRiskEnum risk() default ToolRiskEnum.SAFE;
}
