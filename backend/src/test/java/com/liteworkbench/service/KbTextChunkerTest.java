package com.liteworkbench.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 分块器：段落贪心合并、相邻块重叠衔接、超长段落按步长硬切 */
class KbTextChunkerTest {

    @Test
    void 段落按目标长度贪心合并() {
        String text = ("段落内容。" + "\n\n").repeat(10);
        List<String> chunks = KbTextChunker.chunk(text, 40, 10);
        assertTrue(chunks.size() > 1);
        chunks.forEach(chunk -> assertTrue(chunk.contains("段落内容")));
    }

    @Test
    void 相邻块尾部与下一块头部重叠() {
        String text = "a".repeat(30) + "\n\n" + "b".repeat(30) + "\n\n" + "c".repeat(30);
        List<String> chunks = KbTextChunker.chunk(text, 35, 10);
        assertTrue(chunks.size() >= 2);
        assertTrue(chunks.get(1).contains("a") || chunks.get(1).startsWith("b"));
    }

    @Test
    void 超长段落按步长硬切且不超上限() {
        // 目标 100、重叠 20 → 步长 80：0/80/160/240/320/400 共 6 个窗口
        List<String> chunks = KbTextChunker.chunk("x".repeat(500), 100, 20);
        assertEquals(6, chunks.size());
        chunks.forEach(chunk -> assertTrue(chunk.length() <= 100));
    }
}
