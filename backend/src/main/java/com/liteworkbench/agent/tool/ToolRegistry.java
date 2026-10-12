package com.liteworkbench.agent.tool;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.thread.ThreadUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.liteworkbench.agent.model.ModelTypes.ToolSpec;
import com.liteworkbench.common.OutputSanitizer;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

/** 工具注册表：扫描 @AgentTool 生成 Schema，按名调用并统一超时、异常与输出裁剪 */
@Slf4j
public class ToolRegistry {

    /** 输出保留长度，超出保尾（traceback 末行才是原因） */
    private static final int OUTPUT_MAX = 8000;
    /** 并发执行工具数上限 */
    private static final int POOL_SIZE = 8;

    private final Map<String, ToolDefinition> definitions = new LinkedHashMap<>();

    private final ExecutorService executor = Executors.newFixedThreadPool(POOL_SIZE,
            ThreadUtil.newNamedThreadFactory("lw-tool", true));

    /** 扫描并注册一个工具实现类，Schema 不合法即抛异常（错误左移到启动期） */
    public void register(Object bean) {
        for (Method method : bean.getClass().getDeclaredMethods()) {
            AgentTool annotation = method.getAnnotation(AgentTool.class);
            if (Objects.isNull(annotation)) {
                continue;
            }
            if (!String.class.equals(method.getReturnType())) {
                throw new IllegalStateException(StrUtil.format("工具 {} 必须返回 String", annotation.name()));
            }
            ToolDefinition definition = compile(annotation, method, bean);
            ToolDefinition exists = definitions.putIfAbsent(annotation.name(), definition);
            if (Objects.nonNull(exists)) {
                throw new IllegalStateException(StrUtil.format("工具名重复：{}", annotation.name()));
            }
        }
    }

    /** 已注册工具名 */
    public List<String> names() {
        return new ArrayList<>(definitions.keySet());
    }

    /** 风险等级，未注册返回 SAFE */
    public String riskOf(String name) {
        ToolDefinition definition = definitions.get(name);
        return Objects.isNull(definition) ? null : definition.getRisk().getCode();
    }

    /** 下发给模型的工具描述，enabled 用于过滤平台级启停 */
    public List<ToolSpec> specs(Predicate<String> enabled) {
        List<ToolSpec> specs = new ArrayList<>();
        definitions.values().forEach(definition -> {
            if (Objects.isNull(enabled) || enabled.test(definition.getName())) {
                specs.add(new ToolSpec(definition.getName(), definition.getDescription(),
                        definition.getParameters()));
            }
        });
        return specs;
    }

    /** 按名调用：未知工具、超时、异常统一转 ERROR: 文本，让模型自行换路 */
    public String invoke(String name, String argumentsJson, long timeoutSeconds) {
        ToolDefinition definition = definitions.get(name);
        if (Objects.isNull(definition)) {
            return error(StrUtil.format("未知工具 {}", name));
        }
        JSONObject arguments = parseArguments(argumentsJson);
        Future<String> future = executor.submit(() -> call(definition, arguments));
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            return error(StrUtil.format("工具 {} 执行超时（{}s）", name, timeoutSeconds));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return error(StrUtil.format("工具 {} 执行被中断", name));
        } catch (ExecutionException e) {
            Throwable cause = Objects.isNull(e.getCause()) ? e : e.getCause();
            log.warn("[tool.invoke] name={} 失败 {}", name, cause.getMessage());
            return error(StrUtil.format("工具 {} 执行失败：{}", name, cause.getMessage()));
        }
    }

    /** 关闭执行线程池 */
    public void shutdown() {
        executor.shutdownNow();
    }

    private String call(ToolDefinition definition, JSONObject arguments) {
        Object[] values = new Object[definition.getParamNames().length];
        for (int i = 0; i < values.length; i++) {
            values[i] = convert(arguments, definition.getParamNames()[i], definition.getParamTypes()[i]);
        }
        try {
            Object result = definition.getMethod().invoke(definition.getTarget(), values);
            return cut(OutputSanitizer.clean(Objects.toString(result, StrUtil.EMPTY)));
        } catch (InvocationTargetException e) {
            Throwable cause = Objects.isNull(e.getTargetException()) ? e : e.getTargetException();
            throw new IllegalStateException(Objects.toString(cause.getMessage(), cause.getClass().getSimpleName()));
        } catch (Exception e) {
            throw new IllegalStateException(Objects.toString(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    /** 参数按声明名取用，缺失时基本类型给零值、包装类型给 null，由工具实现自行兜底 */
    private Object convert(JSONObject arguments, String name, Class<?> type) {
        Object value = arguments.get(name);
        if (Objects.isNull(value)) {
            return PrimitiveDefaults.of(type);
        }
        if (String.class.equals(type)) {
            return Convert.toStr(value, StrUtil.EMPTY);
        }
        return switch (type.getName()) {
            case "int" -> Convert.toInt(value, 0);
            case "java.lang.Integer" -> Convert.toInt(value, null);
            case "long" -> Convert.toLong(value, 0L);
            case "java.lang.Long" -> Convert.toLong(value, null);
            case "double" -> Convert.toDouble(value, 0D);
            case "java.lang.Double" -> Convert.toDouble(value, null);
            case "boolean" -> Convert.toBool(value, false);
            case "java.lang.Boolean" -> Convert.toBool(value, null);
            default -> Convert.toStr(value, StrUtil.EMPTY);
        };
    }

    /** 注册期编译：Schema + 反射元数据 */
    private ToolDefinition compile(AgentTool annotation, Method method, Object bean) {
        JSONObject properties = JSONUtil.createObj();
        JSONArray required = JSONUtil.createArray();
        Parameter[] parameters = method.getParameters();
        String[] names = new String[parameters.length];
        Class<?>[] types = new Class<?>[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            types[i] = parameter.getType();
            AgentToolParam param = parameter.getAnnotation(AgentToolParam.class);
            if (Objects.isNull(param)) {
                throw new IllegalStateException(StrUtil.format("工具 {} 的第 {} 个参数缺少 @AgentToolParam",
                        annotation.name(), i + 1));
            }
            names[i] = param.name();
            JSONObject property = JSONUtil.createObj().put("type", resolveType(param, parameter.getType()));
            if (StrUtil.isNotBlank(param.description())) {
                property.put("description", param.description());
            }
            properties.put(param.name(), property);
            if (param.required()) {
                required.put(param.name());
            }
        }
        JSONObject schema = JSONUtil.createObj().put("type", "object").put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return new ToolDefinition(annotation.name(), annotation.description(), annotation.risk(), schema,
                bean, method, names, types);
    }

    private String resolveType(AgentToolParam param, Class<?> javaType) {
        if (StrUtil.isNotBlank(param.type())) {
            return param.type();
        }
        if (String.class.equals(javaType)) {
            return "string";
        }
        return switch (javaType.getName()) {
            case "int", "java.lang.Integer", "long", "java.lang.Long" -> "integer";
            case "double", "java.lang.Double", "float", "java.lang.Float" -> "number";
            case "boolean", "java.lang.Boolean" -> "boolean";
            default -> "string";
        };
    }

    private JSONObject parseArguments(String argumentsJson) {
        if (StrUtil.isBlank(argumentsJson)) {
            return JSONUtil.createObj();
        }
        try {
            return JSONUtil.parseObj(argumentsJson);
        } catch (Exception e) {
            log.warn("[tool.args] 参数不是合法 JSON，按空参数处理 {}", head(argumentsJson));
            return JSONUtil.createObj();
        }
    }

    /** 超长保尾并说明丢弃量 */
    private String cut(String output) {
        String text = Objects.toString(output, StrUtil.EMPTY);
        if (text.length() <= OUTPUT_MAX) {
            return text;
        }
        return StrUtil.format("（已丢弃前 {} 字符）\n{}", text.length() - OUTPUT_MAX,
                text.substring(text.length() - OUTPUT_MAX));
    }

    private String error(String message) {
        return "ERROR: " + message;
    }

    /** 日志用短文本 */
    private String head(String text) {
        String value = Objects.toString(text, StrUtil.EMPTY);
        return value.length() <= 120 ? value : value.substring(0, 120);
    }

    /** 基本类型的缺省值，避免反射调用时 NPE */
    private static final class PrimitiveDefaults {

        private PrimitiveDefaults() {
        }

        static Object of(Class<?> type) {
            if (!type.isPrimitive()) {
                return null;
            }
            if (int.class.equals(type)) {
                return 0;
            }
            if (long.class.equals(type)) {
                return 0L;
            }
            if (double.class.equals(type)) {
                return 0D;
            }
            if (boolean.class.equals(type)) {
                return Boolean.FALSE;
            }
            return null;
        }
    }
}
