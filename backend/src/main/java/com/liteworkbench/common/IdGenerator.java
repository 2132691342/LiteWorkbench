package com.liteworkbench.common;

import com.github.f4b6a3.ulid.UlidCreator;
import com.liteworkbench.domain.constant.IdPrefixConst;

import java.util.Objects;

/** ID 生成器：业务前缀 + ULID，全局唯一入口 */
public final class IdGenerator {

    private IdGenerator() {
    }

    /** 生成带业务前缀的 ID，形如 USER_01H... */
    public static String generate(IdPrefixConst prefix) {
        if (Objects.isNull(prefix)) {
            throw new BizException(ErrorCodeEnum.PARAM_INVALID, "ID 前缀不能为空");
        }
        return prefix.getPrefix() + "_" + UlidCreator.getUlid().toString();
    }

    /** 生成不带前缀的裸 ULID，用于 Redisearch 文档与临时对象 */
    public static String raw() {
        return UlidCreator.getUlid().toString();
    }
}
