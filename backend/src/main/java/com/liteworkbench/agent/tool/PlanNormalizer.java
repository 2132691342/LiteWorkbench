package com.liteworkbench.agent.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import com.liteworkbench.domain.constant.PlanConst;

import java.util.List;
import java.util.Objects;

/** 计划归一化：把模型给的行式清单解析成结构化 JSON，同时生成给模型回看的人读文本 */
public final class PlanNormalizer {

    /** 步骤状态与标记的对应关系，解析与渲染共用一处定义 */
    private record Marker(String mark, String status) {
    }

    private static final List<Marker> MARKERS = List.of(
            new Marker(PlanConst.MARK_DONE, PlanConst.Status.DONE),
            new Marker("[X]", PlanConst.Status.DONE),
            new Marker(PlanConst.MARK_DOING, PlanConst.Status.DOING),
            new Marker("[-]", PlanConst.Status.DOING));

    private PlanNormalizer() {
    }

    /** 解析整份计划：每行一步，行首 [x] / [~] / [ ] 为状态；空行丢弃，超上限截断 */
    public static JSONArray parse(String plan) {
        JSONArray steps = new JSONArray();
        if (StrUtil.isBlank(plan)) {
            return steps;
        }
        int index = 0;
        for (String rawLine : plan.split("\\R")) {
            String line = StrUtil.trimToEmpty(rawLine);
            if (StrUtil.isBlank(line)) {
                continue;
            }
            String status = PlanConst.Status.PENDING;
            for (Marker marker : MARKERS) {
                if (StrUtil.startWithIgnoreCase(line, marker.mark())) {
                    status = marker.status();
                    line = StrUtil.trim(StrUtil.removePrefixIgnoreCase(line, marker.mark()));
                    break;
                }
            }
            // 去掉 "1. " / "1) " 这类模型自带的序号，展示序号由服务端统一给出
            line = stripLeadingNumber(line);
            if (StrUtil.isBlank(line)) {
                continue;
            }
            if (++index > PlanConst.MAX_STEPS) {
                break;
            }
            JSONObject step = new JSONObject();
            step.set("index", index);
            step.set("content", StrUtil.maxLength(line, PlanConst.MAX_STEP_CHARS));
            step.set("status", status);
            steps.add(step);
        }
        return steps;
    }

    /** 归一化后的计划渲染为给模型回看的人读清单 */
    public static String render(JSONArray steps) {
        if (Objects.isNull(steps) || steps.isEmpty()) {
            return "（空计划）";
        }
        StringBuilder text = new StringBuilder();
        for (Object item : steps) {
            JSONObject step = (JSONObject) item;
            String mark = switch (String.valueOf(step.getStr("status"))) {
                case PlanConst.Status.DONE -> PlanConst.MARK_DONE;
                case PlanConst.Status.DOING -> PlanConst.MARK_DOING;
                default -> "[ ]";
            };
            text.append(mark).append(' ').append(step.getStr("index")).append(". ")
                    .append(step.getStr("content")).append('\n');
        }
        return text.toString().trim();
    }

    /** 去掉行首 "1." / "1)" / "1、" 形式的自编号 */
    private static String stripLeadingNumber(String line) {
        int cursor = 0;
        while (cursor < line.length() && Character.isDigit(line.charAt(cursor))) {
            cursor++;
        }
        if (cursor == 0 || cursor >= line.length()) {
            return line;
        }
        char separator = line.charAt(cursor);
        if (separator == '.' || separator == ')' || separator == '、' || separator == '，') {
            return StrUtil.trimStart(line.substring(cursor + 1));
        }
        return line;
    }
}
