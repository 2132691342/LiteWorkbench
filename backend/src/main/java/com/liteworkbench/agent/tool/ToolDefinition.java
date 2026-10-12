package com.liteworkbench.agent.tool;

import cn.hutool.json.JSONObject;
import com.liteworkbench.domain.constant.ToolRiskEnum;
import lombok.Getter;

import java.lang.reflect.Method;

/** 已编译的工具定义：Schema 与反射元数据在注册期一次算清 */
@Getter
public class ToolDefinition {

    /** 调用名 */
    private final String name;
    /** 面向模型的说明 */
    private final String description;
    /** 风险等级 */
    private final ToolRiskEnum risk;
    /** JSON Schema */
    private final JSONObject parameters;
    /** 工具实现实例 */
    private final Object target;
    /** 工具方法 */
    private final Method method;
    /** 参数名，顺序与方法签名一致 */
    private final String[] paramNames;
    /** 参数类型，顺序与方法签名一致 */
    private final Class<?>[] paramTypes;

    public ToolDefinition(String name, String description, ToolRiskEnum risk, JSONObject parameters,
                          Object target, Method method, String[] paramNames, Class<?>[] paramTypes) {
        this.name = name;
        this.description = description;
        this.risk = risk;
        this.parameters = parameters;
        this.target = target;
        this.method = method;
        this.paramNames = paramNames;
        this.paramTypes = paramTypes;
    }
}
