package com.liteworkbench.mapper;

import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Manticore 文档读写共用转换：命中源解析、关键字归一，纯函数无状态 */
public final class ManticoreSupport {

    private ManticoreSupport() {
    }

    /** 命中文档的 _source 转字段表，缺失字段返回空串 */
    public static Map<String, String> sourceMap(Object source) {
        Map<String, String> fields = new LinkedHashMap<>();
        if (source instanceof Map<?, ?> map) {
            map.forEach((key, value) -> fields.put(String.valueOf(key),
                    Objects.isNull(value) ? StrUtil.EMPTY : String.valueOf(value)));
        }
        return fields;
    }

    /** 读取字符串字段 */
    public static String text(Map<String, String> source, String field) {
        return StrUtil.nullToEmpty(source.get(field));
    }

    /** 读取整数字段，缺失或非数字为 0 */
    public static int intValue(Map<String, String> source, String field) {
        return NumberUtil.parseInt(source.get(field), 0);
    }

    /** 读取长整字段，缺失或非数字为 0 */
    public static long longValue(Map<String, String> source, String field) {
        return NumberUtil.parseLong(source.get(field), 0L);
    }

    /** 关键字归一为全文表达式：字母数字下划线与 CJK 保留，其余作分隔，空格连接（默认 AND） */
    public static String fulltextExpression(String keyword) {
        List<String> terms = terms(keyword);
        return terms.isEmpty() ? StrUtil.EMPTY : String.join(" ", terms);
    }

    /** 字段限定表达式：@field:(词1 词2)；无有效词时返回 null 表示不加全文条件 */
    public static String fieldExpression(String field, String keyword) {
        List<String> terms = terms(keyword);
        if (terms.isEmpty()) {
            return null;
        }
        return StrUtil.format("@{}:({})", field, String.join(" ", terms));
    }

    /** 关键字切词：剔除全文语法特殊字符，防查询注入与语法错误 */
    private static List<String> terms(String keyword) {
        List<String> terms = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (char ch : StrUtil.blankToDefault(keyword, "").toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_') {
                current.append(ch);
                continue;
            }
            if (current.length() > 0) {
                terms.add(current.toString());
                current.setLength(0);
            }
        }
        if (current.length() > 0) {
            terms.add(current.toString());
        }
        return terms;
    }
}
