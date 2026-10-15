package com.liteworkbench.observability;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import java.util.Collection;
import java.util.Map;
import java.util.regex.Pattern;

/** 日志脱敏：JSON 递归替换敏感键的值，非 JSON 按 key=value / "key":value 形态正则兜底，再统一截断 */
public final class LogSanitizer {

    /** 脱敏占位符 */
    public static final String MASK = "***";

    private LogSanitizer() {
    }

    /** 键名是否命中敏感词：忽略大小写、下划线与连字符后做包含匹配 */
    public static boolean isSensitive(String name, Collection<String> sensitiveKeys) {
        if (StrUtil.isBlank(name)) {
            return false;
        }
        String flat = flatten(name);
        for (String key : sensitiveKeys) {
            if (flat.contains(flatten(key))) {
                return true;
            }
        }
        return false;
    }

    /** 载荷脱敏并截断：可解析为 JSON 走结构替换，解析失败回退正则替换 */
    public static String mask(String text, Collection<String> sensitiveKeys, int maxChars) {
        String source = StrUtil.isBlank(text) ? "" : text.trim();
        if (source.isEmpty()) {
            return "";
        }
        String masked;
        if (source.startsWith("{") || source.startsWith("[")) {
            try {
                masked = JSONUtil.toJsonStr(maskNode(JSONUtil.parse(source), sensitiveKeys));
            } catch (RuntimeException e) {
                masked = maskRaw(source, sensitiveKeys);
            }
        } else {
            masked = maskRaw(source, sensitiveKeys);
        }
        return StrUtil.sub(masked, 0, maxChars);
    }

    private static Object maskNode(Object node, Collection<String> sensitiveKeys) {
        if (node instanceof JSONObject object) {
            JSONObject copy = new JSONObject();
            for (Map.Entry<String, Object> entry : object.entrySet()) {
                String key = entry.getKey();
                copy.set(key, isSensitive(key, sensitiveKeys) ? MASK : maskNode(entry.getValue(), sensitiveKeys));
            }
            return copy;
        }
        if (node instanceof JSONArray array) {
            JSONArray copy = new JSONArray();
            for (Object item : array) {
                copy.add(maskNode(item, sensitiveKeys));
            }
            return copy;
        }
        return node;
    }

    /** 非 JSON 兜底：表单与查询串形态的 key=value、"key":value 一并替换 */
    private static String maskRaw(String text, Collection<String> sensitiveKeys) {
        String result = text;
        for (String key : sensitiveKeys) {
            result = result.replaceAll("(?i)([\"']?" + Pattern.quote(key) + "[\"']?\\s*[:=]\\s*)([\"']?)([^\"',}&\\s]*)\\2",
                    "$1$2" + MASK + "$2");
        }
        return result;
    }

    private static String flatten(String name) {
        return name.toLowerCase().replace("_", "").replace("-", "");
    }
}
