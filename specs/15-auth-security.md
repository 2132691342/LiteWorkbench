# 15 · 登录安全

## 定位

把"凭据怎么过网、怎么存、怎么失效"定死，避免出现"页面上写着默认密码""登录接口明文传输"这类事故。

边界：只覆盖凭据传输、密码存储、失败锁定、强制改密与登出；权限判定见 spec 11。

## 设计

| 环节 | 做法 |
|---|---|
| 传输 | 密码字段为 `RSA/ECB/PKCS1Padding` 密文（与浏览器端 JSEncrypt 对齐），公钥随请求前取；明文 HTTP 下 WebCrypto 不可用，故不依赖 `crypto.subtle` |
| 存储 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 随机盐，摘要形如 `pbkdf2$迭代次数$盐$Base64`，与盐分列存 `sys_user`；校验兼容旧的 `SHA-256(密码 + 盐)` 格式 |
| 回显 | 明文密码不出现在页面、日志、接口响应、文档中的任何位置 |
| 初值 | 环境变量 `LITEWORKBENCH_ADMIN_PASSWORD`；来自配置时不打印，缺失时随机 16 位强密码并仅启动日志 WARN 打印一次 |
| 首登 | 种子账号 `pwd_reset_required = true`，改密前仅放行两个接口 |
| 失败 | 同用户名连续 5 次失败锁 10 分钟（Redis 计数），另有按 IP 限流 |
| 会话 | Sa-Token 存 Redis，令牌风格 `ulid`，`Authorization` 头裸令牌（不加 `Bearer`） |

密钥对进程内存持有，**每 6 小时或每 1000 次加密轮换**；`/auth/public-key` 返回当前 `keyId`，登录请求带上 `keyId`，服务端按 ID 查私钥，过期 ID 直接判凭据无效。密钥不落盘、不进日志。

## 契约

```java
public interface SecretCodec {

    /** 当前公钥（SPKI 的 Base64），用于前端加密 */
    PublicKeyPayload current();

    /** 按 keyId 解密，密钥不存在或解密失败返回 null */
    String decrypt(String keyId, String cipherBase64);
}
```

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/auth/public-key` | 免登录 | 下发 `{ keyId, algorithm, publicKey, expiresAt }` |
| POST | `/api/v1/auth/login` | 免登录 | `{ username, password(密文), keyId }` |
| POST | `/api/v1/auth/password` | 登录即可 | `{ oldPassword(密文), newPassword(密文), keyId }` |

## 关键流程

```
GET /auth/public-key
  → keyId = k_ + 16 位随机串；私钥留在内存，公钥（SPKI Base64）可安全下发
  → 轮换时旧钥保留一把，在途请求仍能解密

POST /auth/login
  ├─ 1 按 username 查 Redis 失败计数，命中锁定 → ACCOUNT_LOCKED(2007)
  ├─ 2 decrypt(keyId, password)；失败 → CREDENTIAL_INVALID(2002)，计数 +1
  ├─ 3 查用户，比对摘要（PBKDF2，旧格式回退 SHA-256）；失败 → 计数 +1 并统一提示
  ├─ 4 计数清零；StpUtil.login(userId)；写 last_login_at
  └─ 5 返回 token 与 pwdResetRequired

POST /auth/password
  ├─ 1 两段密文解密；新密码强度校验（≥8 位且含字母与数字），否则 PASSWORD_WEAK(2008)
  ├─ 2 校原密码 → 生成新盐与新 PBKDF2 摘要 → pwd_reset_required = false
  └─ 3 StpUtil.logout()，强制用新密码重新登录
```

失败计数键 `lw:auth:fail:{username}`，成功登录即删除；锁定期间不区分"密码错"与"账号不存在"，提示统一为"用户名或密码错误"。

## 前端配合

- `utils/crypto.ts`：`fetchPublicKey()` 缓存 `{keyId, publicKey, expiresAt}`，临期前 30 秒自动续取；`encryptSecret(plain)` 用 JSEncrypt 做 RSA/PKCS#1 v1.5 加密，输出 Base64 密文。
- 公钥取不到时**不降级为明文**，直接提示"安全组件加载失败，请刷新重试"。
- 密码输入框统一 `type="password"`、`autocomplete="current-password" | "new-password"`，不写入 localStorage。
- 登录页不得展示任何默认账号或默认密码的提示文案。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| RSA 加密传输而非直连 HTTPS | 容器只开一个 8080、走纯 HTTP 也能不出现明文 | 公钥获取多一次往返；防不住能改页面的中间人 |
| 用 JSEncrypt 而非 `crypto.subtle` | 明文 HTTP 下（非安全上下文）仍可加密 | 多一个约 50KB 的前端依赖 |
| 密钥对内存持有并轮换 | 无需持久化与证书管理 | 多实例部署需共享私钥，当前单实例不涉及 |
| 首登强制改密 | 消除"默认密码长期在线" | 首次部署多一步操作 |
| 失败锁定 | 抗在线爆破 | 攻击者可借锁定造成小范围拒绝服务，故锁定窗口只 10 分钟 |
| 改密后强制重登 | 旧令牌立即失效 | 用户多一次登录 |

## 约束

- 禁止在 `application.yaml` 里为 `liteworkbench.auth.admin-password` 给非空默认值。
- 禁止把 `password` 字段作为 `String` 打进任何日志、异常消息或事件 `payload`。
- 公钥接口必须带 `@RateLimit`（按 IP 每分钟 60 次），避免轮换密钥被打爆。
