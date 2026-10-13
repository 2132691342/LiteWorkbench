package com.liteworkbench.common;

import cn.hutool.core.util.StrUtil;

/** 工具输出清洗与保尾裁剪 */
public final class OutputSanitizer {

    /** 裁剪后保留的最大字符数 */
    public static final int MAX_CHARS = 8000;
    /** 截断提示语，占位符为 Hutool 的 {} 风格 */
    private static final String TRUNCATE_NOTICE = "...[已丢弃前 {} 字符，仅保留结尾 {} 字符]...";

    private OutputSanitizer() {
    }

    /** 清理控制字符与 ANSI 转义序列，超长时保尾截断 */
    public static String clean(String text) {
        if (StrUtil.isBlank(text)) {
            return "";
        }
        String cleaned = text.replaceAll("\\x1B\\[[0-9;]*[A-Za-z]", "")
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F\\x7F]", "");
        if (cleaned.length() <= MAX_CHARS) {
            return cleaned;
        }
        int dropped = cleaned.length() - MAX_CHARS;
        String notice = StrUtil.format(TRUNCATE_NOTICE, dropped, MAX_CHARS);
        return notice + cleaned.substring(cleaned.length() - MAX_CHARS);
    }
}
