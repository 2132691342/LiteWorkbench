package com.liteworkbench.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 输出清洗：控制字符与 ANSI 转义清除，超长保尾截断 */
class OutputSanitizerTest {

    @Test
    void cleanShouldRemoveAnsiAndControlChars() {
        String dirty = "\u001B[31m错误\u001B[0m\u0007响铃";
        assertEquals("错误响铃", OutputSanitizer.clean(dirty));
    }

    @Test
    void cleanShouldKeepTailWhenTooLong() {
        String text = "x".repeat(9000) + "TRACEBACK-END";
        String cleaned = OutputSanitizer.clean(text);
        assertTrue(cleaned.length() > OutputSanitizer.MAX_CHARS);
        assertTrue(cleaned.endsWith("TRACEBACK-END"), "异常原因必须保留在结尾");
        assertTrue(cleaned.startsWith("...[输出已截断"));
    }

    @Test
    void cleanShouldPassThroughShortText() {
        assertEquals("hello", OutputSanitizer.clean("hello"));
    }
}
