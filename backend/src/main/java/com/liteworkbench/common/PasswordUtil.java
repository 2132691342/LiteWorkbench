package com.liteworkbench.common;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.NumberUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.liteworkbench.domain.constant.AuthConst;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Objects;

/** 口令摘要：PBKDF2-HMAC-SHA256（十万次迭代 + 随机盐），校验兼容旧 SHA-256 格式 */
public final class PasswordUtil {

    /** JDK 内置派生算法 */
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    /** 迭代次数 */
    private static final int ITERATIONS = 100_000;
    /** 派生密钥长度，位 */
    private static final int KEY_LENGTH_BITS = 256;
    /** 新格式标识 */
    private static final String PREFIX = "pbkdf2";
    private static final String SEPARATOR = "$";

    private PasswordUtil() {
    }

    /** 生成随机盐 */
    public static String randomSalt() {
        return RandomUtil.randomString(AuthConst.SALT_LENGTH);
    }

    /** 计算摘要，格式 pbkdf2$迭代次数$盐$Base64 哈希 */
    public static String hash(String rawPassword, String salt) {
        String effectiveSalt = StrUtil.isBlank(salt) ? randomSalt() : salt;
        String encoded = Base64.encode(derive(rawPassword, effectiveSalt, ITERATIONS));
        return StrUtil.format("{}{}{}{}{}{}", PREFIX, SEPARATOR, ITERATIONS, SEPARATOR,
                effectiveSalt, SEPARATOR + encoded);
    }

    /** 校验密码：pbkdf2 格式按迭代参数重算，其余按旧 SHA-256(密码+盐) 比对 */
    public static boolean matches(String rawPassword, String salt, String passwordHash) {
        if (StrUtil.isBlank(passwordHash)) {
            return false;
        }
        String[] parts = passwordHash.split("\\$");
        if (parts.length == 4 && PREFIX.equals(parts[0])) {
            int iterations = NumberUtil.parseInt(parts[1], 0);
            if (iterations <= 0) {
                return false;
            }
            byte[] expected = Base64.decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(rawPassword, parts[2], iterations));
        }
        return Objects.equals(DigestUtil.sha256Hex(rawPassword + Objects.toString(salt, "")), passwordHash);
    }

    /** PBKDF2 派生 */
    private static byte[] derive(String rawPassword, String salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(StrUtil.nullToEmpty(rawPassword).toCharArray(),
                StrUtil.bytes(salt, StandardCharsets.UTF_8), iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new BizException(ErrorCodeEnum.INTERNAL_ERROR, "口令摘要失败");
        } finally {
            spec.clearPassword();
        }
    }
}
