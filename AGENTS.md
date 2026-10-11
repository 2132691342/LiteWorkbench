# AGENTS.md

工程规范，唯一权威。文档与代码不同步视为未完成。

文档只回答三件事：项目是什么、每个模块怎么设计实现、为什么这么选型。不写变更过程，不写外部参考来源。

---

## 1. 分层与依赖方向

```
api ──► service ──► mapper ──► domain
  │         │          │
  └─────────┴──────────┴──► domain/constant

config ──► domain/constant
mq     ──► domain/dto
agent  ──► service（通过接口回调），不反向依赖 api
runtime──► 无业务依赖
observability ──► common + domain/constant
anno ──► 无依赖；aspect ──► common + domain/constant
```

| 层 | 职责 | 禁止 |
|---|---|---|
| `api` | 参数校验、调用 service、封装响应 | 写业务逻辑、直接调 mapper |
| `service` | 业务编排、事务边界、日志 | 引用 HTTP 类型、直接写 SQL |
| `mapper` | 数据库 / Redisearch / Redis / MinIO 访问 | 写业务判断 |
| `domain` | 实体、传输对象、枚举常量 | 依赖任何上层 |
| `config` | 配置类与属性绑定 | 写业务逻辑 |
| `anno` / `aspect` | 自定义注解与切面（幂等、限流、审计） | 持有业务状态 |
| `agent` | AgentScope 装配与事件适配 | 引用 `api` 层 |
| `runtime` | 内置 Python 运行时的探测与执行 | 依赖业务包 |

**规则**：`service` 与 `api` 不得就地声明入参出参结构体，一律引用 `domain.dto.XxxREQ` / `domain.vo.XxxRESP`。

## 2. 包与命名

### 2.1 domain

| 子包 | 内容 | 命名 |
|---|---|---|
| `entity` | 实体映射（库表 / Redis / Redisearch） | `XxxDO.java` |
| `dto` | 请求与内部传输 | `XxxREQ.java`、`XxxDTO.java` |
| `vo` | 响应与视图对象 | `XxxRESP.java`、`XxxVO.java` |
| `constant` | 枚举与静态常量 | `XxxEnum.java`、`XxxConst.java` |

**实体包命名为 `entity`**：`do` 是 Java 保留字不能作包名，类名仍保持 `XxxDO`。

**静态常量一律用接口**，不写 `final class` + 私有构造：

```java
public interface SessionConst {
    int TITLE_MAX_LENGTH = 128;
    String DEFAULT_MODEL = "qwen-plus";
}
```

枚举用 `@Getter` + `@AllArgsConstructor`，带 `code` 与 `description`，并提供 `ofCode` 静态查找。

### 2.2 其他包

| 包 | 命名 |
|---|---|
| `mapper` | `XxxMapper.java` + `resources/mapper/XxxMapper.xml` |
| `service` | `XxxService.java`（接口在需要多实现时才拆） |
| `api` | `XxxController.java` |
| `config` | `XxxConfig.java`、`XxxProperties.java` |
| `anno` | `Xxx.java`（注解名不带后缀） |
| `aspect` | `XxxAspect.java` |
| `mq.producer` | `XxxProducer.java` |
| `mq.consumer` | `XxxConsumer.java` |

## 3. 角色与权限

不做多租户。多用户、多角色、多菜单权限，以**角色**为单位授权，数据归属以**用户**为单位隔离。

### 3.1 数据归属模型

- 用户拥有的业务表（会话、运行、事件、消息、技能、文件、设置、任务）带 `user_id varchar(40)`（`USER_` 前缀），行级隔离。
- 平台级表不带 `user_id`，仅管理角色可写：`sys_menu`、`sys_user_role`、`sys_role_menu`、`wb_tool`、`wb_provider`。
- 用户身份由 `common.LoginContext` 提供，禁止从请求体采信 `userId`；Service 在请求线程内一律 `LoginContext.userId()` 取当前用户。请求上下文之外（异步线程、MQ 消费）必须把 `userId` 作为第一个业务参数显式传递。
- 用户拥有的资源，Mapper 查询条件必须显式携带 `user_id`（平台级表除外）。

### 3.2 认证与鉴权

- 认证统一走 Sa-Token，登录态存 Redis，令牌放 `Authorization` 头。
- 接口权限用注解声明，权限标识格式 `域:资源:动作`，如 `system:user:create`：

```java
@SaCheckPermission("system:user:create")
@PostMapping("/user/create")
public R<String> create(@RequestBody @Valid UserREQ req) { ... }
```

- 角色码内置三类：`super_admin`（超级管理员，内置不可删）、`admin`（平台管理员）、`user`（普通用户）；支持按需新建自定义角色，角色下挂多个用户。
- 前端菜单与按钮由 `sys_menu` 驱动，接口返回当前用户可见菜单树与权限标识集合。

### 3.3 数据归属校验

访问单个资源时，先按 `(资源 ID, user_id)` 查；查不到即 `RESOURCE_NOT_FOUND`，不泄露他人资源是否存在。

## 4. API 约定

- **只允许 GET 与 POST**，禁止 PUT / PATCH / DELETE。
- **路径参数必须放末尾**：`/user/delete/{userId}`，禁止 `/user/{userId}/delete`。
- 路径统一 `/api/v1` 前缀，动词化的操作路径用 `动作/资源` 结构：`/session/update`、`/run/abort/{runId}`。
- 分页请求：GET + query 参数 `page`、`size`、`keyword`；列表响应统一 `PageResult`。
- 响应信封统一 `R<T>`：`code` / `message` / `data` / `requestId` / `timestamp`；`code` 必须是 `String`，与 HTTP 状态码的 int 区分，字段一律用包装类型。

| 场景 | 写法 |
|---|---|
| 分页查询 | `GET /api/v1/user/page?page=1&size=20` |
| 详情 | `GET /api/v1/user/detail/{userId}` |
| 新建 | `POST /api/v1/user/create` |
| 修改 | `POST /api/v1/user/update` |
| 删除 | `POST /api/v1/user/delete/{userId}` |
| 启停 | `POST /api/v1/user/toggle/{userId}` |

## 5. 幂等与切面

- 所有写接口（create / update / delete / toggle）必须加 `@Idempotent`：

```java
@Idempotent(prefix = "user", ttl = 10)
@PostMapping("/user/create")
public R<String> create(@RequestBody @Valid UserREQ req) { ... }
```

- 切面实现：`Redis SETNX lw:idem:{prefix}:{userId}:{参数摘要}`，命中即抛 `REPEAT_SUBMIT` 并在响应头回传原结果键；TTL 到期自动释放。
- 其他横切能力（操作审计、耗时打点）优先用注解 + 切面实现，不侵入 Service。

## 6. ID 规范

- 全部使用 ULID，禁止雪花 ID 与 UUID。
- 主键格式：`业务前缀_` + ULID，例 `USER_01M4KC0R1VKDSWQJ9917T2NBQE`。
- 生成入口只有 `common.IdGenerator`，禁止在业务代码里直接调 ULID 库。

| 前缀 | 业务 |
|---|---|
| `USER_` | 用户 |
| `ROLE_` | 角色 |
| `MENU_` | 菜单 / 权限点 |
| `SESS_` | 会话 |
| `RUN_` | 运行 |
| `EVNT_` | 事件 |
| `MESG_` | 会话消息 |
| `TOOL_` | 工具定义 |
| `SKIL_` | 技能 |
| `FILE_` | 文件对象 |
| `PROV_` | 模型供应方 |
| `TASK_` | 异步任务（预留） |
| `SETG_` | 系统设置 |
| `LOGD_` | 日志文档（Redisearch） |

## 7. 字段规范

- 命名完整，不带歧义：`role_name` 而非 `name`，`session_id` 而非 `id` 做外键。
- 审计字段按需取用，出现即按下表语义：

| 字段 | 类型 | 语义 |
|---|---|---|
| `created_by` | varchar | 创建人 ID |
| `created_at` | timestamptz | 创建时间 |
| `updated_by` | varchar | 更新人 ID |
| `updated_at` | timestamptz | 更新时间 |
| `deleted` | boolean | 逻辑删除，默认 false |
| `state` | varchar | 业务状态枚举码 |
| `remark` | varchar | 备注 |

- 时间统一 `timestamptz`，对外 JSON 输出毫秒时间戳（long）。
- 布尔字段一律 `is_` 前缀命名（`is_stream`）会导致 MyBatis 映射麻烦，故库中用 `deleted` / `enabled` 形容词形式。

## 8. 编码约定：优先 Hutool

| 场景 | 用法 | 禁止 |
|---|---|---|
| 对象判空 | `Objects.nonNull(x)` / `Objects.isNull(x)` | `x != null` / `x == null` |
| 对象比较 | `Objects.equals(a, b)` | `a.equals(b)` |
| 字符串 | `StrUtil.isBlank` / `isNotBlank` / `format` / `sub` / `split` | 手写判空拼接 |
| 集合 | `CollUtil.isEmpty` / `isNotEmpty` / `newArrayList` | `list == null \|\| list.isEmpty()` |
| 集合工具 | `CollectionUtil`、`MapUtil`、`ArrayUtil` | 重复造轮子 |
| 树结构 | `TreeUtil`（菜单树） | 手写递归 |
| 日期 | `DateUtil`、`LocalDateTimeUtil` | `SimpleDateFormat` |
| JSON | `JSONUtil.toJsonStr` / `toBean` / `toList` | 业务里到处注入 ObjectMapper |
| 加解密 | `SecureUtil`、`DigestUtil` | 手写摘要 |
| 文件 | `FileUtil`、`IoUtil` | 裸流未关闭 |
| 断言 | `Assert.isTrue`（Hutool） | 抛裸异常 |

## 9. 日志约定

- 长链路、复杂流程必须打点：`开始 → 关键分支 → 结束`，带耗时与业务 ID。
- 使用 `slf4j` + `lombok.@Slf4j`（或 `@Log4j2`），禁止 `System.out`。
- 日志按天 + 按级别滚动，共三个文件：`liteworkbench.log`（全量 INFO+）、`liteworkbench-error.log`（WARN/ERROR 单独）、`liteworkbench-agent.log`（Agent 链路专用），保留 30 天；目录开发期默认 `logs/`，容器内用 `LITEWORKBENCH_LOG_DIR` 覆盖。
- 业务日志同时异步投递到 Redisearch，供页面检索。
- 禁止打印密钥、Token、完整请求体中的敏感字段。

```java
log.info("[run.start] userId={} sessionId={} runId={} model={}", userId, sessionId, runId, model);
long cost = System.currentTimeMillis() - start;
log.info("[run.end] runId={} state={} cost={}ms", runId, state, cost);
```

## 10. 配置约定

- 所有外部化配置集中在 `config` 包，用 `@ConfigurationProperties` 绑定。
- 相关的一组属性用**内部类**挂在同一个配置类下，不散落成多个文件：

```java
@ConfigurationProperties(prefix = "liteworkbench.agent")
public class AgentProperties {
    private Model model = new Model();
    private Loop loop = new Loop();

    public static class Model { private String defaultName; /* ... */ }
    public static class Loop { private int maxIters = 20; /* ... */ }
}
```

- 必填配置在 `application.yaml` 给默认值，缺失时启动失败（fail fast）。
- 密钥走环境变量，不入库、不进日志。

## 11. 错误码

分段制，见 [docs/ERROR-CODES.md](docs/ERROR-CODES.md)。统一抛 `common.BizException`：

```java
throw new BizException(ErrorCodeEnum.SESSION_NOT_FOUND, sessionId);
```

全局异常处理器统一转成 `R.fail(code, message)`，不把堆栈返回前端。

## 12. 前端规范

| 项 | 约定 |
|---|---|
| 组件 | PascalCase 单文件组件，`<Domain><Thing>.vue` |
| 目录 | `views/` 路由页，`components/layout/` 布局件、`components/ui/` 基础件、`components/charts/` 图表件、`components/<domain>/` 业务件 |
| Store | Pinia setup 风格 `defineStore('x', () => {...})`，大对象用 `shallowRef` |
| 请求 | 唯一出口 `src/api/index.ts`，字段名与后端 JSON 严格一致 |
| 类型 | `src/types/api.ts` 与后端 VO 对齐，禁止 `any` 泛滥 |
| 样式 | UnoCSS 原子类 + `var(--lw-*)` 令牌，禁止硬编码色值 |
| 按钮 | 一律使用 `components/ui/LwButton.vue`，实现五态（见 DESIGN.md） |
| 国际化 | 文案一律走 `vue-i18n`，组件内不写中文硬编码 |
| 状态 | 每个异步操作必须有 loading / error / empty 三态 |
| 路由 | 动态菜单由后端 `sys_menu` 下发，路由守卫校验登录态与权限标识 |

## 13. 注释规范

精简，只写"是什么"和"边界"，"为什么"归文档。

| 位置 | 要求 |
|---|---|
| 文件头 | 一行说明文件职责 |
| 导出方法 | 一行签名说明 |
| 字段 | 有歧义的必须注释 |
| 连续注释块 | 不超过 2 行 |

## 14. 测试约定

只保留有针对性、覆盖复杂流程与长链路的测试，删除琐碎断言。

| 类型 | 范围 |
|---|---|
| 单元测试 | ULID 生成、事件归一化、输出裁剪、技能触发词匹配、权限码匹配 |
| 集成测试 | 会话 → 运行 → 事件落库 → 回放 的完整链路 |
| 契约测试 | SSE 事件序列顺序与字段；登录 → 鉴权 → 数据归属 链路 |
