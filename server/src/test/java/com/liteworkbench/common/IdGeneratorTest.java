package com.liteworkbench.common;

import com.liteworkbench.domain.constant.IdPrefixConst;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ULID 生成器：前缀、长度与匹配规则 */
class IdGeneratorTest {

    @Test
    void generateShouldCarryBusinessPrefixAndUlid() {
        String id = IdGenerator.generate(IdPrefixConst.USER);
        assertTrue(id.startsWith("USER_"));
        // 前缀 4 + 下划线 1 + ULID 26
        assertEquals(IdPrefixConst.USER.getPrefix().length() + 27, id.length());
        assertTrue(id.substring(5).matches("[0-9A-HJKMNP-TV-Z]{26}"));
    }

    @Test
    void generateShouldRejectNullPrefix() {
        assertThrows(BizException.class, () -> IdGenerator.generate(null));
    }

    @Test
    void rawShouldBeBareUlid() {
        assertEquals(26, IdGenerator.raw().length());
    }

    @Test
    void matchesShouldJudgePrefixOwnership() {
        String sessionId = IdGenerator.generate(IdPrefixConst.SESSION);
        assertTrue(IdGenerator.matches(sessionId, IdPrefixConst.SESSION));
        assertFalse(IdGenerator.matches(sessionId, IdPrefixConst.RUN));
    }
}
