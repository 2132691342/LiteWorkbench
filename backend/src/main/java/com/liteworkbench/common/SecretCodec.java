package com.liteworkbench.common;

import cn.hutool.core.codec.Base64;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** 登录凭据加解密：RSA 密钥对进程内存持有，按时间或次数轮换，旧钥保留一轮兜住在途请求 */
@Slf4j
@Component
public class SecretCodec {

    /** JDK 内置算法 */
    private static final String ALGORITHM = "RSA";
    /** 与前端 JSEncrypt 对齐的填充方式 */
    private static final String TRANSFORMATION = "RSA/ECB/PKCS1Padding";
    /** 密钥长度 */
    private static final int KEY_SIZE = 2048;
    /** 轮换周期 */
    private static final long ROTATE_INTERVAL_MS = Duration.ofHours(6L).toMillis();
    /** 轮换使用次数上限 */
    private static final int ROTATE_USE_LIMIT = 1000;

    /** 当前密钥 */
    private volatile Entry current;
    /** 上一把密钥，用于兜住轮换瞬间的在途登录 */
    private volatile Entry previous;

    /** 启动即生成首把密钥，避免首个登录请求承担生成开销 */
    @PostConstruct
    public void init() {
        this.current = create();
        log.info("[secret.init] 登录加密公钥就绪 keyId={}", current.keyId);
    }

    /** 当前公钥标识，必要时先完成轮换 */
    public String keyId() {
        ensure();
        return current.keyId;
    }

    /** 当前公钥（SPKI 的 Base64），必要时先完成轮换 */
    public String publicKeyBase64() {
        ensure();
        return current.publicKeyBase64;
    }

    /** 当前公钥过期毫秒时间戳 */
    public long expiresAt() {
        ensure();
        return current.expiresAt;
    }

    /** 按 keyId 解密，密钥不存在或解密失败返回 null，调用方统一按凭据无效处理 */
    public String decrypt(String keyId, String cipherBase64) {
        if (StrUtil.isBlank(keyId) || StrUtil.isBlank(cipherBase64)) {
            return null;
        }
        // 解密路径也参与轮换判定：前端缓存公钥纯登录时，仅靠公钥下发点计数会漏计
        ensure();
        Entry entry = resolve(keyId);
        if (Objects.isNull(entry)) {
            log.warn("[secret.decrypt] 公钥已轮换或不存在 keyId={}", keyId);
            return null;
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, entry.keyPair.getPrivate());
            entry.uses.incrementAndGet();
            return new String(cipher.doFinal(Base64.decode(cipherBase64)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[secret.decrypt] 解密失败 keyId={} {}", keyId, e.getMessage());
            return null;
        }
    }

    private Entry resolve(String keyId) {
        Entry snapshot = current;
        if (Objects.nonNull(snapshot) && Objects.equals(snapshot.keyId, keyId)) {
            return snapshot;
        }
        Entry stale = previous;
        return Objects.nonNull(stale) && Objects.equals(stale.keyId, keyId) ? stale : null;
    }

    /** 轮换判定：超过使用次数或超过有效期即换新钥 */
    private void ensure() {
        if (usable(current)) {
            return;
        }
        synchronized (this) {
            if (usable(current)) {
                return;
            }
            previous = current;
            current = create();
            log.info("[secret.rotate] 登录加密密钥已轮换 keyId={}", current.keyId);
        }
    }

    private boolean usable(Entry entry) {
        return Objects.nonNull(entry)
                && entry.uses.get() < ROTATE_USE_LIMIT
                && System.currentTimeMillis() < entry.expiresAt;
    }

    private Entry create() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(ALGORITHM);
            generator.initialize(KEY_SIZE);
            KeyPair keyPair = generator.generateKeyPair();
            Entry entry = new Entry();
            entry.keyId = "k_" + RandomUtil.randomString(16);
            entry.keyPair = keyPair;
            entry.publicKeyBase64 = Base64.encode(keyPair.getPublic().getEncoded());
            entry.expiresAt = System.currentTimeMillis() + ROTATE_INTERVAL_MS;
            return entry;
        } catch (Exception e) {
            throw new IllegalStateException("登录加密密钥生成失败", e);
        }
    }

    /** 单把密钥及其使用计数 */
    private static final class Entry {

        private String keyId;
        private String publicKeyBase64;
        private KeyPair keyPair;
        private long expiresAt;
        private final AtomicInteger uses = new AtomicInteger();
    }
}
