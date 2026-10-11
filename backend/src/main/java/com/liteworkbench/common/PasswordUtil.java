package com.liteworkbench.common;

import cn.hutool.core.util.RandomUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.liteworkbench.domain.constant.AuthConst;

import java.util.Objects;

/** 口令摘要：SHA-256(密码 + 盐)，盐为随机字符串 */
public final class PasswordUtil {

    private PasswordUtil() {
    }

    /** 生成随机盐 */
    public static String randomSalt() {
        return RandomUtil.randomString(AuthConst.SALT_LENGTH);
    }

    /** 计算摘要 */
    public static String hash(String rawPassword, String salt) {
        return DigestUtil.sha256Hex(rawPassword + salt);
    }

    /** 校验密码是否匹配 */
    public static boolean matches(String rawPassword, String salt, String passwordHash) {
        return Objects.equals(hash(rawPassword, salt), passwordHash);
    }
}
