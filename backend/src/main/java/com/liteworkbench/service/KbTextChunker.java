package com.liteworkbench.service;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.List;

/** 知识库分块器：段落贪心合并到目标块长，相邻块带重叠衔接，纯静态无依赖 */
public final class KbTextChunker {

    /** 最小目标块长，防止配置失当产生空转 */
    private static final int MIN_TARGET_SIZE = 50;

    private KbTextChunker() {
    }

    /** 按目标块长与重叠切分正文，产出可直接入索引的块列表 */
    public static List<String> chunk(String text, int targetSize, int overlap) {
        int size = Math.max(targetSize, MIN_TARGET_SIZE);
        int over = Math.max(overlap, 0);
        String normalized = StrUtil.nullToEmpty(text).replace("\r\n", "\n").strip();
        if (normalized.isEmpty()) {
            return List.of();
        }
        List<String> pieces = splitOversize(normalized, size, over);
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String piece : pieces) {
            if (current.length() == 0) {
                current.append(piece);
                continue;
            }
            if (current.length() + piece.length() + 2 <= size) {
                current.append("\n\n").append(piece);
                continue;
            }
            chunks.add(current.toString());
            String tail = overlapTail(current.toString(), over, piece.length(), size);
            current = new StringBuilder(StrUtil.isBlank(tail) ? tail : tail + "\n\n").append(piece);
        }
        if (current.length() > 0) {
            chunks.add(current.toString());
        }
        return chunks;
    }

    /** 先按空行切段，超长段再做硬切窗口，保证单段不超目标块长 */
    private static List<String> splitOversize(String normalized, int size, int overlap) {
        List<String> pieces = new ArrayList<>();
        for (String paragraph : normalized.split("\n{2,}")) {
            String trimmed = paragraph.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.length() <= size) {
                pieces.add(trimmed);
                continue;
            }
            for (int start = 0; start < trimmed.length(); start += Math.max(size - overlap, 1)) {
                int end = Math.min(start + size, trimmed.length());
                pieces.add(trimmed.substring(start, end));
                if (end >= trimmed.length()) {
                    break;
                }
            }
        }
        return pieces;
    }

    /** 上一块的尾部衔接：受重叠上限与"不留超过目标块长"双重约束 */
    private static String overlapTail(String previous, int overlap, int nextLength, int size) {
        if (overlap <= 0 || previous.length() <= overlap) {
            return "";
        }
        int usable = Math.min(overlap, Math.max(size - nextLength - 2, 0));
        if (usable <= 0) {
            return "";
        }
        return previous.substring(previous.length() - usable);
    }
}
