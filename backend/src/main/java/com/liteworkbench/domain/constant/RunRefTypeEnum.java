package com.liteworkbench.domain.constant;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;

/** 运行期引用类型：前端 @ 引用在输入框里插入任务上下文时使用 */
@Getter
@AllArgsConstructor
public enum RunRefTypeEnum {

    /** 知识库：按当前问题预取相关分块 */
    KB("kb", "知识库"),
    /** 文件空间里的文件：文本类直接读正文注入 */
    FILE("file", "文件"),
    /** 创作文档：注入正文 */
    DOC("doc", "创作文档"),
    ;

    /** 类型码 */
    private final String code;
    /** 类型描述 */
    private final String description;

    /** 按码反查，未知码返回 null（入参校验交给调用方） */
    public static RunRefTypeEnum ofCode(String code) {
        if (StrUtil.isBlank(code)) {
            return null;
        }
        return Arrays.stream(values())
                .filter(item -> Objects.equals(item.code, code))
                .findFirst()
                .orElse(null);
    }
}
