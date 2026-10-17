# 06 · 安全：角色权限与登录安全

## 定位

管住四件事：**谁能进来**（认证）、**凭据怎么过网、怎么存**（登录安全）、**数据归谁**（用户归属）、**能做什么**（RBAC 功能授权）。

边界：本模块负责身份、凭据与授权判定的实现，不负责业务数据本身；业务模块只消费 `userId` 与权限注解。不回答：角色模型与数据归属的不变量（见 AGENTS.md §3）、权限点命名与菜单 i18n key 规范（见 AGENTS.md §2.5、§3.2）、接口路径与信封（见 docs/API-CONTRACT.md）、部署侧默认口令与密钥注入（见 deploy/）。

## 设计

### 数据归属

- 不做多租户。共享库共享表 + 行级 `user_id`，不做分库，不做 MyBatis 全局拦截器——归属条件显式写进每条 SQL。
- 用户拥有的资源（会话、运行、事件、技能、文件、知识库与文档、创作文档与版本、长期记忆）只允许归属用户读写；与之对应的 Mapper（`WbSessionMapper` / `WbRunMapper` / `WbSkillMapper` / `WbFileMapper` / `WbKbMapper` / `WbKbDocMapper` / `WbDocMapper` / `WbDocVersionMapper` / `WbMemoryMapper` / `SysUserMapper`）查询条件一律携带 `user_id = #{userId}`。
- 平台级表不带归属过滤，由权限点控制写入：`sys_role`、`sys_menu`、`sys_role_menu`、`wb_tool`、`wb_provider`、`wb_skill_seed`。`sys_user_role` 以 `user_id` 为关系键，但它记录的是授权关系而非用户资产，读写同样走权限点。
- 身份唯一起点是 `LoginContext`：请求线程内 `LoginContext.userId()` 取当前用户，先读 ThreadLocal、回退 Sa-Token 会话快照，未登录抛 `UNAUTHORIZED`；禁止从请求体采信 `userId`。请求上下文之外（异步线程、MQ 消费）把 `userId` 作为第一个业务参数显式传递。
- 单资源访问按 `(主键, user_id)` 查询（如 `selectByIdAndUser` / `getOwned`），查不到直接 `RESOURCE_NOT_FOUND`，不泄露他人资源是否存在。

### 账号与角色

| 项 | 规则 |
|---|---|
| 归属 | 用户名全局唯一（`deleted = false` 范围内，唯一索引 `uk_sys_user_username`） |
| 口令 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 16 位随机盐，256 位派生密钥，存储格式 `pbkdf2$迭代次数$盐$Base64`；校验兼容旧 `SHA-256(密码 + 盐)` 记录 |
| 存储字段 | `sys_user.password_hash`（varchar 256）与 `password_salt`（varchar 32）分列存 |
| 状态 | `normal` / `disabled` / `locked`，停用后登录直接拒绝 |
| 自操作 | 不允许停用、删除自己，不允许修改自己的角色 |

| 角色码 | 名称 | 范围 |
|---|---|---|
| `super_admin` | 超级管理员 | 全部菜单与权限点，内置不可删 |
| `admin` | 平台管理员 | 系统管理与工作台全部功能，不持角色管理权限 |
| `user` | 普通用户 | 工作台功能 + 自己数据的增删改权限点 + 本人视角的日志中心 |
| — | 自定义角色 | 按需勾选菜单与权限点；勾选 `monitor:log:global` 即获得全局日志视角 |

- 内置角色码集中在 `RoleCodeConst` 定义，`isBuiltin` 判定内置；角色与菜单多对多（`sys_role_menu`）。
- 角色不提供停用入口：需要回收权限时直接删除角色，或调整其菜单授权。删除受两道保护——内置角色 `BUILTIN_ROLE_IMMUTABLE`，已分配给用户 `ROLE_IN_USE`。
- 角色码由登录会话快照提供（`StpInterfaceImpl.getRoleList` 读会话内 `LoginUser`），授权变更后由 `RoleService.evictPermCache` 清权限缓存、清菜单缓存并刷新会话快照，下一次请求即时生效。
- **赋权守门**：给角色授菜单时，被授出菜单的权限点必须是操作人已持有权限的子集，越界抛 `FORBIDDEN`；给用户分配角色时，角色的权限点同样必须是操作人权限的子集，防止横向提拔与纵向越权。

### 菜单与权限点

- 树形三级：`dir` 目录 / `menu` 路由页 / `button` 权限点；`perm_code` 格式 `域:资源:动作`，配置在 `PermConst`。
- `menu_name` 存 i18n key（规则见 AGENTS.md §2.5），前端经 `menuLabel()` 渲染，翻译缺失也不裸显 key。
- `visible = false` 的菜单不进用户菜单树（管理树仍可见可维护），用于临时下线入口而不删定义。
- 种子 ID 为 `MENU_` + 26 位零填充序号；菜单对账在 `db/data.sql` 内完成：先软删"`route_path` 或 `perm_code` 命中种子但 ID 不在种子内"的残留行，再 upsert 全部结构字段（名称、路由、组件、权限码、图标、排序）；已下线菜单按固定 ID 显式软删并摘除授权。显隐与状态是运营字段，已存在行不覆盖。
- 权限契约由测试兜住，见 AGENTS.md §14：控制器注解与 `PermConst` 声明的权限点必须都存在于菜单种子（否则没人能被授予）；种子里不得出现 `PermConst` 之外的权限点（控制器不认）；控制器注解不得裸写字面量；前端 `hasPerm` / `v-perm` 引用的权限点必须在 `PermConst` 里（写错就是按钮对所有人不显示）；菜单种子的 `component` 必须命中 `frontend/src/views/*.vue`（缺文件则动态路由注册不上，点开即 404）。
- 批量接口（用户启停 / 用户删除 / 文件删除 / 技能删除）复用单条动作的权限点，不新增权限标识；语义是**逐条守门不回滚整批**：不满足规则的目标（删除或停用自己、他人资源、受保护桶）进 `failures` 并附原因，其余照常执行。批量启停的目标状态按首个合法目标推导并整批统一，避免出现半启用半停用。

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
| 供应方 | `wb:provider:list` / `create` / `update` / `delete` |
| 监控 | `monitor:health:list` / `monitor:log:list` / `monitor:log:global` / `monitor:python:reprobe` / `monitor:observe:reset` |
| 存储桶 | `monitor:storage:list` / `create` / `delete` |

`monitor:log:list` 是日志中心入口（按钮级权限，内置 `user` 角色已授予），检索范围固定本人；`monitor:log:global` 是**视角开关**，命中即全局检索与全局用量归因，只授予管理角色。

### 登录安全

| 环节 | 做法 |
|---|---|
| 传输 | 密码字段为 `RSA/ECB/PKCS1Padding` 密文，公钥由 `GET /auth/public-key` 下发（SPKI 的 Base64） |
| 存储 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 随机盐，摘要与盐分列存 `sys_user` |
| 初值 | 种子账号初始口令取环境变量 `LITEWORKBENCH_ADMIN_PASSWORD`（`liteworkbench.auth.admin-password`，默认空）；缺失时随机生成并仅在启动日志 WARN 打印一次。已激活（`pwd_reset_required = false`）的账号不再被配置项覆盖；两个种子账号都存在时不再解析口令，避免每次启动都重新生成并打印 |
| 首登 | 种子账号与管理员新建用户的 `pwd_reset_required = true`，改密前只放行 `/api/v1/auth/current`、`/api/v1/auth/password`、`/api/v1/auth/logout`、`/api/v1/auth/public-key`，其余接口返回 `PASSWORD_RESET_REQUIRED` |
| 失败 | 同用户名连续 5 次失败锁 10 分钟，另有登录接口按 IP 限流 |
| 会话 | Sa-Token 存 Redis，令牌走 `Authorization` 头（裸令牌，不加 `Bearer`），有效期 7200 秒，令牌风格 ULID |
| 加密因子 | 供应方密钥的 AES 因子来自 `LITEWORKBENCH_SECURITY_SECRET`；容器镜像内置 `SPRING_PROFILES_ACTIVE=prod`，仍为内置开发因子时启动自检直接拒绝启动（`StartupGuardService`），本地无 profile 运行只打 WARN |

- 密钥对进程内存持有，**每 6 小时或每 1000 次解密调用轮换**（解密路径也参与轮换判定，否则前端纯登录缓存公钥时会漏计）。`keyId` 形如 `k_` + 16 位随机串，`/auth/public-key` 返回当前 `keyId`，登录请求带上 `keyId`，服务端按 ID 查私钥，过期 ID 与解密失败都返回 null；轮换时旧钥保留一把兜住在途请求。密钥不落盘、不进日志。
- 密码字段不做长度校验（RSA-2048 密文 Base64 后 344 字符，`@Size` 加在密文字段必然误判），口令强度一律在服务端解密后的明文上判定（≥8 位且含字母与数字）。
- 凭据绝不回显：页面、接口响应、运行日志一律不出初始密码或明文密码。明文口令只允许出现在部署编排文件（见 deploy/）与"未配置 `LITEWORKBENCH_ADMIN_PASSWORD` 时随机生成、仅启动日志打印一次"这一例外。

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
| GET | `/api/v1/auth/public-key` | 免登录 | 下发 `{ keyId, algorithm, publicKey, expiresAt }`，按 IP 限流每分钟 60 次 |
| POST | `/api/v1/auth/login` | 免登录 | `{ username, password(密文), keyId }`，按 IP 限流每分钟 10 次 |
| POST | `/api/v1/auth/logout` | 登录即可 | 作废当前令牌，幂等 |
| GET | `/api/v1/auth/current` | 登录即可 | 当前用户、角色码、权限标识与菜单树 |
| POST | `/api/v1/auth/password` | 登录即可 | `{ oldPassword(密文), newPassword(密文), keyId }`，成功后强制重登，幂等 |

鉴权链路：`SaTokenConfig` 注册 `SaInterceptor`（对 `/api/**` 校验登录，白名单 `public-key` / `login` / `actuator/health`）与 `LoginContextInterceptor`。权限判定走 `StpInterface` 实现：权限标识优先读缓存 `lw:cache:perm:{userId}`（Redis List，空集合用 `__EMPTY__` 占位防穿透，TTL 10 分钟），未命中回源数据库，Redis 故障时直接回源不中断鉴权；角色码取会话快照。控制器用 `@SaCheckPermission` 声明权限点，仅需登录的接口不加注解（拦截器兜底）。菜单树另有一层缓存（`RedisKeyConst.cacheMenu:{userId}`，TTL 10 分钟）。

## 关键流程

```
POST /auth/login
  ├─ 1 按 username 读 Redis 失败计数，达到 5 → ACCOUNT_LOCKED
  ├─ 2 decrypt(keyId, password)；失败 → CREDENTIAL_INVALID，计数 +1
  ├─ 3 查用户，比对摘要（PBKDF2，旧格式回退 SHA-256）；失败 → 计数 +1 并统一提示
  ├─ 4 状态非 normal → ACCOUNT_DISABLED
  ├─ 5 计数清零；装配 LoginUser 快照；StpUtil.login()；写 last_login_at
  └─ 6 返回 token、用户信息、pwdResetRequired 与菜单权限标识

鉴权（每请求）
  ├─ TraceFilter → traceId 入 MDC
  ├─ SaInterceptor：登录态校验
  ├─ LoginContextInterceptor：惰性装配 LoginUser 入 LoginContext，userId 注入 MDC；
  │    停用账号 → ACCOUNT_DISABLED；未改密种子账号（非白名单路径）→ PASSWORD_RESET_REQUIRED
  └─ afterCompletion / 异常路径清理 LoginContext 与 MDC（防线程复用串号）

POST /auth/password
  ├─ 1 两段密文解密；任一失败 → PAYLOAD_DECRYPT_FAILED
  ├─ 2 校原密码，不符 → OLD_PASSWORD_INVALID
  ├─ 3 新密码强度校验（≥8 位且含字母与数字），否则 PASSWORD_WEAK；新密码不得与原密码相同
  └─ 4 生成新盐与新 PBKDF2 摘要 → pwd_reset_required = false → StpUtil.logout()，强制用新密码重新登录
```

失败计数键 `lw:auth:fail:{username}`：首次失败在一个 Lua 脚本内原子完成 `INCR` + `EXPIRE`（保住固定窗口语义，避免 `expire` 丢失把用户永久锁死），成功登录即删除；锁定期间不区分"密码错"与"账号不存在"，提示统一为"用户名或密码错误"。授权变更失效：`grant` / 角色删除 / 用户改角色 → 清该角色或该用户的权限缓存与菜单缓存并刷新会话快照，下一次请求回源重建。

管理侧口令入口同样是密文：新建用户、管理员重置密码两处的密码字段解密失败统一 `PAYLOAD_DECRYPT_FAILED`，设密后 `pwd_reset_required` 置 true 并刷新在线用户会话快照使其立即进入强制改密态；删除与停用用户即踢下线并清缓存。

## 前端配合

- `utils/crypto.ts`：`fetchPublicKey()` 缓存 `{keyId, publicKey, expiresAt}`（临期前 30 秒视为过期，并发请求合并为一次），`encryptSecret(plain)` 做 RSA/PKCS#1 v1.5 加密输出 Base64 密文，`resetPublicKey()` 丢弃缓存。
- 公钥取不到时**不降级为明文**，直接提示"安全组件加载失败，请刷新重试"。
- 密码输入框统一 `type="password"`，不写入 localStorage；登录页不展示任何账号或口令提示文案。
- 菜单可见性由后端下发，按钮可见性用 `v-perm` 指令或 `usePermission().hasPerm()`（详见 [07-frontend.md](07-frontend.md)）。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 不做租户，用户归属隔离 | 模型简单，无跨租户越权面 | 组织级汇总需按 `user_id` 聚合 |
| 行级隔离显式写 SQL | 行为透明，性能可预期 | 依赖开发规范，漏写即越权 |
| 权限集合缓存 | 每请求零查库 | 授权变更需主动失效 |
| RSA 加密传输而非直连 HTTPS | 容器只开一个端口、走纯 HTTP 也不出现明文 | 公钥获取多一次往返；防不住能改页面的中间人 |
| 密钥对内存持有并轮换 | 无需持久化与证书管理 | 多实例部署需共享私钥，当前单实例不涉及 |
| 首登强制改密 | 消除"默认口令长期在线" | 首次部署多一步操作 |
| 初始口令缺失即随机生成 | 缺配置时失败关闭而不是退回众所周知的弱口令 | 需从启动日志取一次口令 |
| 失败锁定 | 抗在线爆破 | 攻击者可借锁定造成小范围拒绝服务，故窗口只 10 分钟 |
| 改密后强制重登 | 旧令牌立即失效 | 用户多一次登录 |
| 赋权守门（授出权限 ≤ 自身） | 杜绝横向提拔与纵向越权 | 授权时多一次权限子集判定 |

## 约束

- 用户资源 Mapper 查询必须显式带 `user_id`；平台级表（`sys_role`、`sys_menu`、`sys_role_menu`、`sys_user_role`、`wb_tool`、`wb_provider`、`wb_skill_seed`）除外。
- 明文口令只允许出现在部署编排文件（见 deploy/）；禁止出现在页面、接口响应、日志与事件 `payload` 中；唯一例外是未配置 `LITEWORKBENCH_ADMIN_PASSWORD` 时随机生成的口令，仅在启动日志 WARN 打印一次。
- 公钥接口必须带按 IP 限流，避免轮换密钥被打爆；登录接口另有按 IP 的 `@RateLimit`。
- 权限标识一经发布不得改名，废弃只做下线处理；内置角色码在 `RoleCodeConst` 集中定义，禁止散落字面量。
- 密码字段不做长度校验，强度只在服务端解密后的明文上判定。
