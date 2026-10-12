# 11 · 用户角色与权限

## 定位

管住三件事：**谁能进来**（认证）、**数据归谁**（用户归属）、**能做什么**（RBAC 功能授权）。

边界：本模块负责主数据与判定逻辑，不负责业务数据；业务模块只消费 `userId` 与权限注解。

## 设计

### 数据归属

- 不做多租户。共享库共享表 + 行级 `user_id`，不做分库，不做 MyBatis 全局拦截器。
- 用户拥有的资源（会话、运行、事件、消息、技能、文件、知识库与知识库文档、创作文档与版本、提示词、工作流）只允许归属用户读写；管理页面对象（用户、角色、菜单、工具、供应方）由权限点控制。

### 账号

| 项 | 规则 |
|---|---|
| 归属 | 用户全局唯一，`UNIQUE(username)`（`deleted = false` 范围内） |
| 口令 | `PBKDF2-HMAC-SHA256`，十万次迭代 + 16 位随机盐，存储格式 `pbkdf2$迭代次数$盐$Base64`；校验兼容旧 `SHA-256(密码 + 盐)` 记录 |
| 初值 | 种子账号密码取自 `liteworkbench.auth.admin-password`，未配置则随机生成并只打印一次；账号置 `pwd_reset_required = true` |
| 状态 | `normal` / `disabled` / `locked`，停用后登录直接拒绝 |
| 自操作 | 不允许停用、删除自己，不允许修改自己的角色 |

凭据传输与首登强制的完整规则见 [spec 15](15-auth-security.md)。

### 角色

| 角色码 | 名称 | 范围 |
|---|---|---|
| `super_admin` | 超级管理员 | 全部菜单与权限点，内置不可删 |
| `admin` | 平台管理员 | 系统管理与工作台全部功能 |
| `user` | 普通用户 | 用户级功能：仪表盘、会话、文件、技能、知识库、创作台、提示词、工作流 |

- 内置角色码不可改不可删；支持新建自定义角色，按角色分配菜单与权限点。
- 角色与菜单多对多（`sys_role_menu`），授权变更后立即清除相关用户权限缓存。

### 菜单与权限点

- 树形三级：`dir` 目录 / `menu` 路由页 / `button` 权限点。
- `perm_code` 格式 `域:资源:动作`，例：`system:user:create`、`wb:session:delete`。
- `menu_name` 存 i18n key（`menu.userManage` / `menu.groupSystem` / `menu.userManageCreate`），禁止"同段既是叶子又是分组"，规则见 AGENTS.md §2.5；前端经 `menuLabel()` 渲染，翻译缺失也不裸显 key。
- 种子 ID 为 `MENU_ + 26 位零填充序号`；启动时按 ID upsert 回写全部字段，并软删 `route_path` 或 `perm_code` 命中种子但 ID 不在种子内的历史残留行。
- 前端菜单由后端下发，按钮可见性由 `perms` 集合控制。

### 会话（Sa-Token）

- 登录态存 Redis，令牌走 `Authorization` 头（值为令牌本身，不加 `Bearer` 前缀），有效期 7200 秒。
- 权限判定走 `StpInterface` 实现：优先读 `lw:cache:perm:{userId}`（TTL 10 分钟），未命中回源数据库。
- 控制器用 `@SaCheckPermission` 声明权限点；仅需登录的接口用 `@SaCheckLogin` 或不加注解（拦截器兜底）。

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
```

```java
/** Sa-Token 权限数据源 */
public class StpInterfaceImpl implements StpInterface {
    /** 返回用户权限标识集合，带缓存 */
    public List<String> getPermissionList(Object loginId, String loginType);
    /** 返回用户角色码集合 */
    public List<String> getRoleList(Object loginId, String loginType);
}
```

| 服务方法 | 说明 |
|---|---|
| `AuthService.login(LoginREQ)` | 登录是鉴权前唯一入口，返回 token、用户、角色码、权限标识、菜单树 |
| `AuthService.current()` | 当前用户完整信息，登录后前端首个请求 |
| `RoleService.grant(RoleGrantREQ, userId)` | 按角色重挂菜单并清缓存 |
| `MenuService.userTree()` | 当前用户可见菜单树，身份取 `LoginContext` |

除 `login` 外，Service 在请求线程内一律经 `LoginContext.userId()` 取当前用户（见 AGENTS.md §3.1）。

### 权限点清单

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
| 提示词 | `wb:prompt:list` / `create` / `update` / `delete`（`list` 同时用于变量渲染） |
| 工作流 | `wb:flow:list` / `create` / `update` / `delete` / `run` |
| 供应方 | `wb:provider:list` / `create` / `update` / `delete`（`update` 同时用于连通性测试） |
| 监控 | `monitor:health:list` / `monitor:log:list` / `monitor:python:reprobe` |

## 关键流程

```
登录 POST /auth/login
  ├─ 1 查用户 (username)，校验密码与账号状态（不存在/停用统一失败提示）
  ├─ 2 StpUtil.login(userId)，Sa-Token 写会话
  ├─ 3 组装 LoginUser 与权限集合，写 lw:cache:perm:{userId}
  └─ 4 记录 last_login_at，返回 token 与前端所需一切

鉴权（每请求）
  ├─ TraceFilter → traceId
  ├─ Sa-Token 拦截器：登录态校验 → 权限注解校验
  ├─ LoginContext 注入 LoginUser
  └─ finally 清理 ThreadLocal
```

授权变更失效：`grant` / 角色停用 / 角色删除 → 删除该角色下所有用户的 `lw:cache:perm:*`，下一次请求回源重建。

## 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 不做租户，用户归属隔离 | 模型简单，单用户视角清晰，无跨租户越权面 | 组织级汇总需按 `user_id` 聚合 |
| 行级隔离显式写 SQL | 行为透明，性能可预期 | 依赖开发规范，漏写即越权 |
| 权限集合缓存 | 每请求零查库 | 授权变更需主动失效 |
| 角色码内置三种 | 前端与后端判断简单 | 内置角色不可删除 |
| 登录失败统一提示 | 不泄露用户枚举 | 排障依赖日志 |

## 约束

- 用户资源 Mapper 查询必须显式带 `user_id`；平台级表（`sys_menu`、`sys_user_role`、`sys_role_menu`、`wb_tool`、`wb_provider`）除外。
- 密码、盐、token 禁止进入日志与响应。
- 权限标识一经发布不得改名，废弃只做下线处理。
- 内置角色码（`super_admin` / `admin` / `user`）在常量接口 `RoleCodeConst` 集中定义，禁止散落字面量。
