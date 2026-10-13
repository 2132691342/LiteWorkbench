# 06 · 安全：角色权限与登录安全

## 定位

管住四件事：**谁能进来**（认证）、**凭据怎么过网、怎么存**（登录安全）、**数据归谁**（用户归属）、**能做什么**（RBAC 功能授权）。

边界：本模块负责主数据与判定逻辑，不负责业务数据；业务模块只消费 `userId` 与权限注解。

## 设计

### 数据归属

- 不做多租户。共享库共享表 + 行级 `user_id`，不做分库，不做 MyBatis 全局拦截器。
- 用户拥有的资源（会话、运行、事件、消息、技能、文件、知识库与文档、创作文档与版本、提示词）只允许归属用户读写；管理页面对象（用户、角色、菜单、工具、供应方）由权限点控制。
- 单资源访问按 `(主键, user_id)` 查询，查不到直接 `RESOURCE_NOT_FOUND`，不泄露他人资源是否存在。

### 账号与角色

| 项 | 规则 |
|---|---|
| 归属 | 用户名全局唯一（`deleted = false` 范围内） |
| 口令 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 16 位随机盐，存储格式 `pbkdf2$迭代次数$盐$Base64`；校验兼容 `SHA-256(密码 + 盐)` 记录 |
| 状态 | `normal` / `disabled` / `locked`，停用后登录直接拒绝 |
| 自操作 | 不允许停用、删除自己，不允许修改自己的角色 |

| 角色码 | 名称 | 范围 |
|---|---|---|
| `super_admin` | 超级管理员 | 全部菜单与权限点，内置不可删 |
| `admin` | 平台管理员 | 系统管理与工作台全部功能 |
| `user` | 普通用户 | 用户级功能 + 自己数据的增删改权限点 |
| — | 自定义角色 | 按需勾选菜单与权限点；勾选 `monitor:log:global` 即获得全局日志视角 |

角色与菜单多对多（`sys_role_menu`），授权变更后立即清除相关用户的权限缓存。角色不提供停用入口：需要回收权限时直接删除角色（`ROLE_IN_USE` 保护已分配用户）或调整其菜单授权。

### 菜单与权限点

- 树形三级：`dir` 目录 / `menu` 路由页 / `button` 权限点；`perm_code` 格式 `域:资源:动作`。
- `menu_name` 存 i18n key（规则见 AGENTS.md §2.5），前端经 `menuLabel()` 渲染，翻译缺失也不裸显 key。
- `visible = false` 的菜单不进用户菜单树（管理树仍可见可维护），用于临时下线入口而不删定义。
- 种子 ID 为 `MENU_ + 26 位零填充序号`；启动时按 ID upsert 回写全部字段（名称、路由、组件、权限码、图标、排序），并软删"`route_path` 或 `perm_code` 命中种子但 ID 不在种子内"的残留行；已下线菜单按固定 ID 显式清理（软删 + 摘除授权）。

**权限点清单**：

| 域 | 权限标识 |
|---|---|
| 用户 | `system:user:list` / `create` / `update` / `reset` / `delete` |
| 角色 | `system:role:list` / `create` / `update` / `grant` / `delete` |
| 菜单 | `system:menu:list` / `create` / `update` / `delete` |
| 工具 | `wb:tool:list` / `wb:tool:toggle` / `wb:tool:invoke` |
| 技能 | `wb:skill:list` / `create` / `update` / `delete` |
| 文件 | `wb:file:delete`（其余动作登录即可用） |
| 知识库 | `wb:kb:list` / `create` / `update` / `delete` / `upload` / `search` |
| 创作文档 | `wb:doc:list` / `create` / `update` / `delete` / `search` |
| 提示词 | `wb:prompt:list` / `create` / `update` / `delete` |
| 供应方 | `wb:provider:list` / `create` / `update` / `delete` |
| 监控 | `monitor:health:list` / `monitor:log:list` / `monitor:log:global` / `monitor:python:reprobe` |

`monitor:log:list` 是日志中心入口（登录即可分配的按钮级权限），检索范围固定本人；`monitor:log:global` 是**视角开关**，命中即全局检索，只授予管理角色。

### 登录安全

| 环节 | 做法 |
|---|---|
| 传输 | 密码字段为 `RSA/ECB/PKCS1Padding` 密文（与浏览器端 JSEncrypt 对齐），公钥随请求前取；明文 HTTP 下 WebCrypto 不可用，故不依赖 `crypto.subtle` |
| 存储 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 随机盐，摘要与盐分列存 `sys_user` |
| 初值 | 种子账号口令取环境变量 `LITEWORKBENCH_ADMIN_PASSWORD`（编排文件给的默认值仅供私有部署便利）；仅对未激活账号（`pwd_reset_required = true`）生效，账号完成自助改密后配置项不再覆盖 |
| 首登 | 种子账号 `pwd_reset_required = true`，改密前只放行 `/auth/current`、`/auth/password`、`/auth/logout` 与 `/auth/public-key` |
| 失败 | 同用户名连续 5 次失败锁 10 分钟（Redis 固定窗口：首次失败置 TTL，窗口内累加，成功登录清零），另有按 IP 限流 |
| 会话 | Sa-Token 存 Redis，令牌走 `Authorization` 头（裸令牌，不加 `Bearer`），有效期 7200 秒 |

密钥对进程内存持有，**每 6 小时或每 1000 次解密调用轮换**（加密发生在前端，只能按服务端解密次数计）；`/auth/public-key` 返回当前 `keyId`，登录请求带上 `keyId`，服务端按 ID 查私钥，过期 ID 直接判凭据无效；轮换时旧钥保留一把兜住在途请求。密钥不落盘、不进日志。密码字段不做长度校验（RSA-2048 密文 Base64 后 344 字符），口令强度一律在服务端解密后的明文上判定。

## 契约

```java
/** 请求线程内的登录上下文 */
public final class LoginContext {

    /** 当前登录用户：先读 ThreadLocal，回退 Sa-Token 会话，未登录返回 null */
    public static LoginUser get();

    /** 当前用户 ID，未登录抛 UNAUTHORIZED */
    public static String userId();

    /** 请求结束时清理 ThreadLocal */
    public static void clear();
}

/** 登录口令加密封装：密钥对进程内存持有，按 6 小时或 1000 次解密轮换 */
@Component
public class SecretCodec {

    /** 当前公钥 ID，随 /auth/public-key 下发 */
    public String keyId();

    /** 当前公钥（SPKI 的 Base64），用于前端加密 */
    public String publicKeyBase64();

    /** 当前密钥对过期时间（毫秒） */
    public long expiresAt();

    /** 按 keyId 解密，密钥不存在或解密失败返回 null */
    public String decrypt(String keyId, String cipherBase64);
}
```

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/auth/public-key` | 免登录 | 下发 `{ keyId, algorithm, publicKey, expiresAt }`，按 IP 限流 |
| POST | `/api/v1/auth/login` | 免登录 | `{ username, password(密文), keyId }` |
| GET | `/api/v1/auth/current` | 登录即可 | 当前用户、角色码、权限标识与菜单树 |
| POST | `/api/v1/auth/password` | 登录即可 | `{ oldPassword(密文), newPassword(密文), keyId }`，成功后强制重登 |

权限判定走 `StpInterface` 实现：优先读缓存 `lw:cache:perm:{userId}`（TTL 10 分钟），未命中回源数据库；控制器用 `@SaCheckPermission` 声明权限点，仅需登录的接口用 `@SaCheckLogin` 或不加注解（拦截器兜底）。

## 关键流程

```
POST /auth/login
  ├─ 1 按 username 查 Redis 失败计数，命中锁定 → ACCOUNT_LOCKED
  ├─ 2 decrypt(keyId, password)；失败 → CREDENTIAL_INVALID，计数 +1
  ├─ 3 查用户，比对摘要（PBKDF2，旧格式回退 SHA-256）；失败 → 计数 +1 并统一提示
  ├─ 4 计数清零；StpUtil.login(userId)；写 last_login_at
  └─ 5 返回 token、用户信息、pwdResetRequired 与菜单权限标识

鉴权（每请求）
  ├─ TraceFilter → traceId
  ├─ Sa-Token 拦截器：登录态校验 → 权限注解校验 → LoginUser 入 LoginContext 并把 userId 注入 MDC
  └─ finally 清理 LoginContext 与 MDC

POST /auth/password
  ├─ 1 两段密文解密；任一失败 → PAYLOAD_DECRYPT_FAILED
  ├─ 2 校原密码，不符 → OLD_PASSWORD_INVALID
  ├─ 3 新密码强度校验（≥8 位且含字母与数字），否则 PASSWORD_WEAK；新密码不得与原密码相同
  └─ 4 生成新盐与新 PBKDF2 摘要 → pwd_reset_required = false → StpUtil.logout()，强制用新密码重新登录
```

失败计数键 `lw:auth:fail:{username}`，成功登录即删除；锁定期间不区分"密码错"与"账号不存在"，提示统一为"用户名或密码错误"。授权变更失效：`grant` / 角色删除 → 删除该角色下所有用户的权限缓存，下一次请求回源重建。

## 前端配合

- `utils/crypto.ts`：`fetchPublicKey()` 缓存 `{keyId, publicKey, expiresAt}`，临期前自动续取；`encryptSecret(plain)` 做 RSA/PKCS#1 v1.5 加密，输出 Base64 密文。
- 公钥取不到时**不降级为明文**，直接提示"安全组件加载失败，请刷新重试"。
- 密码输入框统一 `type="password"`，不写入 localStorage；登录页不展示任何账号或口令提示文案。
- 菜单可见性由后端下发，按钮可见性用 `v-perm` 指令或 `usePermission().hasPerm()`。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 不做租户，用户归属隔离 | 模型简单，无跨租户越权面 | 组织级汇总需按 `user_id` 聚合 |
| 行级隔离显式写 SQL | 行为透明，性能可预期 | 依赖开发规范，漏写即越权 |
| 权限集合缓存 | 每请求零查库 | 授权变更需主动失效 |
| RSA 加密传输而非直连 HTTPS | 容器只开一个 8080、走纯 HTTP 也不出现明文 | 公钥获取多一次往返；防不住能改页面的中间人 |
| 用 JSEncrypt 而非 `crypto.subtle` | 明文 HTTP 下仍可加密 | 多一个前端依赖 |
| 密钥对内存持有并轮换 | 无需持久化与证书管理 | 多实例部署需共享私钥，当前单实例不涉及 |
| 首登强制改密 | 消除"默认口令长期在线" | 首次部署多一步操作 |
| 失败锁定 | 抗在线爆破 | 攻击者可借锁定造成小范围拒绝服务，故窗口只 10 分钟 |
| 改密后强制重登 | 旧令牌立即失效 | 用户多一次登录 |

## 约束

- 用户资源 Mapper 查询必须显式带 `user_id`；平台级表（`sys_menu`、`sys_user_role`、`sys_role_menu`、`wb_tool`、`wb_provider`）除外。
- 明文口令只允许出现在部署编排文件（私有部署便利），禁止出现在页面、接口响应、日志与事件 `payload` 中；唯一例外是未配置 `LITEWORKBENCH_ADMIN_PASSWORD` 时随机生成的口令，仅在启动日志 WARN 打印一次。
- 公钥接口必须带按 IP 限流，避免轮换密钥被打爆；登录接口另有按 IP 的 `@RateLimit`。
- 权限标识一经发布不得改名，废弃只做下线处理；内置角色码在 `RoleCodeConst` 集中定义，禁止散落字面量。
