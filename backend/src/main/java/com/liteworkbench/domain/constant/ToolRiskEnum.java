package com.liteworkbench.domain.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/** 工具风险等级：决定页面手工调用是否需要二次确认，以及 Agent 内是否交给审批闸门 */
@Getter
@AllArgsConstructor
public enum ToolRiskEnum {

    SAFE("safe", "只读"),
    CONFIRM("confirm", "有副作用"),
    DANGER("danger", "危险"),
    ;

    /** 等级码，与 wb_tool.risk_level 一致 */
    private final String code;
    /** 描述 */
    private final String description;

    /** 按码反查；未知码按 SAFE 处理，避免历史数据阻断执行 */
    public static ToolRiskEnum ofCode(String code) {
        for (ToolRiskEnum item : values()) {
            if (item.code.equalsIgnoreCase(code)) {
                return item;
            }
        }
        return SAFE;
    }
}
