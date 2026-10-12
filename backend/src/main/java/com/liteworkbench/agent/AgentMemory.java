package com.liteworkbench.agent;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.liteworkbench.agent.model.ModelTypes.Msg;
import com.liteworkbench.agent.model.ModelTypes.ToolCall;
import com.liteworkbench.agent.model.ModelTypes.Role;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 上下文组装与确定性压缩：协议配对修复 + token 估算 + 在 user 边界裁切，全程不调模型 */
public final class AgentMemory {

    /** 非 CJK 字符的 token 折算比 */
    private static final double LATIN_CHARS_PER_TOKEN = 3.5D;
    /** 每条消息的固定开销 */
    private static final int MESSAGE_OVERHEAD = 4;
    /** 每次工具调用的固定开销 */
    private static final int TOOL_CALL_OVERHEAD = 8;

    private AgentMemory() {
    }

    /** 组装上下文：系统提示词在首，历史已修复协议并按预算裁切，本轮输入在尾 */
    public static List<Msg> assemble(List<Msg> history, String systemPrompt, String prompt,
                                     int contextWindow, int reserveTokens) {
        List<Msg> messages = new ArrayList<>();
        if (StrUtil.isNotBlank(systemPrompt)) {
            messages.add(Msg.system(systemPrompt));
        }
        if (CollUtil.isNotEmpty(history)) {
            messages.addAll(clean(history));
        }
        messages.add(Msg.user(StrUtil.nullToEmpty(prompt)));
        return compact(messages, (long) contextWindow - reserveTokens);
    }

    /** 协议修复：丢掉空助手消息与孤儿工具结果，避免上游端点直接报参数错误 */
    public static List<Msg> clean(List<Msg> messages) {
        List<Msg> cleaned = new ArrayList<>(messages.size());
        List<String> declaredCalls = new ArrayList<>();
        for (Msg message : messages) {
            if (Objects.isNull(message) || StrUtil.isBlank(message.getRole())) {
                continue;
            }
            if (Role.ASSISTANT.equals(message.getRole())) {
                if (StrUtil.isBlank(message.getContent())
                        && CollUtil.isEmpty(message.getToolCalls())) {
                    continue;
                }
                if (CollUtil.isNotEmpty(message.getToolCalls())) {
                    message.getToolCalls().stream().map(ToolCall::getId).forEach(declaredCalls::add);
                }
                cleaned.add(message);
                continue;
            }
            if (Role.TOOL.equals(message.getRole()) && !declaredCalls.contains(message.getToolCallId())) {
                continue;
            }
            cleaned.add(message);
        }
        return cleaned;
    }

    /** 估算 token：CJK 一字一 token，其余按 3.5 字符一 token */
    public static long estimate(List<Msg> messages) {
        long total = 0L;
        for (Msg message : messages) {
            total += MESSAGE_OVERHEAD;
            String content = StrUtil.nullToEmpty(message.getContent());
            long cjk = content.codePoints().filter(AgentMemory::isCjk).count();
            total += cjk;
            total += Math.round((content.length() - cjk) / LATIN_CHARS_PER_TOKEN);
            if (CollUtil.isNotEmpty(message.getToolCalls())) {
                total += (long) message.getToolCalls().size() * TOOL_CALL_OVERHEAD;
            }
        }
        return total;
    }

    /** 超预算时整轮丢弃最老的历史，保留系统提示词与末尾完整的用户轮次，裁不出整轮就放弃裁切 */
    public static List<Msg> compact(List<Msg> messages, long budget) {
        if (budget <= 0 || messages.size() <= 2 || estimate(messages) <= budget) {
            return messages;
        }
        int head = isSystem(messages.get(0)) ? 1 : 0;
        List<Msg> kept = new ArrayList<>(messages);
        while (estimate(kept) > budget) {
            int boundary = -1;
            for (int i = head + 1; i < kept.size(); i++) {
                if (Role.USER.equals(kept.get(i).getRole())) {
                    boundary = i;
                    break;
                }
            }
            if (boundary < 0 || boundary >= kept.size() - 1) {
                break;
            }
            List<Msg> trimmed = new ArrayList<>(kept.subList(0, boundary));
            trimmed.addAll(kept.subList(boundary + 1, kept.size()));
            kept = trimmed;
        }
        int dropped = messages.size() - kept.size();
        if (dropped > 0) {
            kept.add(head, compressNotice(dropped));
        }
        return kept;
    }

    /** 压缩提示：插在系统提示词之后，告知模型历史被裁 */
    private static Msg compressNotice(int dropped) {
        return Msg.user(StrUtil.format("[系统提示] 为控制上下文长度，已省略更早的 {} 条历史消息。", dropped));
    }

    private static boolean isSystem(Msg message) {
        return Role.SYSTEM.equals(message.getRole());
    }

    private static boolean isCjk(int codePoint) {
        return (codePoint >= 0x4E00 && codePoint <= 0x9FFF)
                || (codePoint >= 0x3400 && codePoint <= 0x4DBF)
                || (codePoint >= 0xF900 && codePoint <= 0xFAFF);
    }
}
