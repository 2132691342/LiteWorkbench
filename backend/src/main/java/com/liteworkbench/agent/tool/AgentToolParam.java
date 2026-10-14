package com.liteworkbench.agent.tool;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 工具参数声明：Java 不保留参数名，故必须显式给出 name */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface AgentToolParam {

    /** 参数名，模型可见 */
    String name();

    String description() default "";

    boolean required() default true;

    /** JSON Schema 类型，留空则按参数 Java 类型推断 */
    String type() default "";
}
