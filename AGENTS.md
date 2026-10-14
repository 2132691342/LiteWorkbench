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
agent  ──► domain + config + runtime + agent/spi（自身端口），端口实现在 service
runtime──► 无业务依赖
observability ──► common + domain/constant
anno ──► 无依赖；aspect ──► common + domain/constant
```

| 层 | 职责 | 禁止 |
|---|---|---|
| `api` | 参数校验、调用 service、封装响应 | 写业务逻辑、直接调 mapper |
| `service` | 业务编排、事务边界、日志 | 引用 HTTP 类型、直接写 SQL |
| `mapper` | 数据库 / Manticore / Redis / MinIO 访问 | 写业务判断 |
| `domain` | 实体、传输对象、枚举常量 | 依赖任何上层 |
| `config` | 配置类与属性绑定 | 写业务逻辑 |
| `anno` / `aspect` | 自定义注解与切面（幂等、限流、审计） | 持有业务状态 |
| `agent` | 自研 Agent 内核（推理循环、工具、模型协议适配、事件归一）；只读取平台级配置 | 引用 `api` 层、直接访问 `mapper`、反向依赖 `service` 实现 |
| `runtime` | 内置 Python 运行时的探测与执行 | 依赖业务包 |

**规则**：`service` 与 `api` 不得就地声明入参出参结构体，一律引用 `domain.dto.XxxREQ` / `domain.vo.XxxRESP`。

## 2. 包与命名

### 2.1 domain

| 子包 | 内容 | 命名 |
|---|---|---|
| `entity` | 实体映射（库表 / Redis / Manticore） | `XxxDO.java` |
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

枚举用 `@Getter` + `@AllArgsConstructor`，带 `code` 与 `description`；只有确实需要按码反查的地方（如状态机判定）才提供 `ofCode`，没人调用的不写。

**实体只用 `@Data` 不够**，按需要叠加构造与链式能力，`entity` 下的库表 / Redis / Manticore 映射默认组合如下：

```java
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
public class SysUserDO implements Serializable { ... }
```

| 注解 | 作用 | 何时去掉 |
|---|---|---|
| `@Data` | getter / setter / 常用方法 | 几乎不去；只读对象换 `@Getter` |
| `@Builder` | 链式建造，装配处少写多行 setter | 字段全由 ORM 回填、无需手工构造时可去 |
| `@NoArgsConstructor` | MyBatis 反射回填必需 | 不去 |
| `@AllArgsConstructor` | 与 `@Builder` 配套 | 不去 |
| `@Accessors(chain = true)` | setter 返回自身，连续赋值 | 不希望 setter 有返回值时去掉 |

字段带默认值时必须加 `@Builder.Default`，否则 Builder 会吞掉初始值。

`vo` / `dto` 至少保留 `@Data` 与无参构造（不可变传输对象用 `record`，不加任何 Lombok 注解）；调用方需要链式装配时，再按上表叠加 `@Builder` 与 `@Accessors`。

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

### 2.3 Mapper：注解优先，XML 兜底

单表增删改查一律写注解，只有确实需要动态标签或长 SQL 时才落 XML，**不允许默认建 XML**。

| 形态 | 判据 | 写法 |
|---|---|---|
| 单表 insert / update / delete / select，无分支 | 静态 SQL | `@Insert` / `@Update` / `@Delete` / `@Select`，SQL 用 `"""` 文本块 |
| 含 `<if>` / `<choose>` / `<foreach>` / `<set>` | 动态拼接 | XML |
| 多表 join、聚合统计、窗口函数 | SQL 长且需要可读性 | XML |

同一个 Mapper 允许注解与 XML 共存：XML 里只留上述复杂语句，且不得与注解方法重名。

**写入 `jsonb` 列必须显式 `CAST(#{x} AS jsonb)`**：PG 不做 varchar 参数到 jsonb 的隐式转换。现有列：`wb_event.payload`、`wb_prompt.variables`、`wb_tool.schema_json`。

可空判断用 SQL 空值兜底，保持语句静态：

```java
@Select("""
        SELECT COUNT(1) FROM sys_user
        WHERE username = #{username} AND deleted = false
          AND (#{excludeId} IS NULL OR #{excludeId} = '' OR user_id <> #{excludeId})
        """)
long countByUsername(@Param("username") String username, @Param("excludeId") String excludeId);
```

### 2.4 依赖注入：构造注入

一律**构造注入**，配合 `@RequiredArgsConstructor`，字段保持 `private final`。禁止 `@Autowired` 字段注入，禁止为每个依赖手写构造器。

```java
@Slf4j
@Service
@RequiredArgsConstructor
public class RunService {
    private final WbRunMapper runMapper;
    private final Map<String, ActiveRun> active = new ConcurrentHashMap<>(); // 已初始化的 final 不进构造器
}
```

需要 JDK 能力或 Hutool 已有能力时**直接调用**，不为其写单行私有包装方法；只有真正被多处复用的转换才抽方法（如 Manticore 文档字段批量转码）。

### 2.5 菜单与 i18n key

`sys_menu.menu_name` 存 **i18n key**，不存中文，前端经 `menuLabel()` 渲染。

**key 命名禁止"同段既能当叶子又能当分组"**：`menu.system`（叶子）与 `menu.system.user`（另一叶子）在 vue-i18n 的点路径解析下会互相遮蔽，导致菜单退化成裸 key 或英文。

| 形态 | 写法 | 反例 |
|---|---|---|
| 路由菜单 | `menu.<camelCase>`，如 `menu.userManage` | `menu.system.user` |
| 目录 | `menu.groupSystem` | `menu.system` 同时被当叶子用 |
| 权限点 | `menu.<路由key><Action>`，如 `menu.userManageCreate` | `menu.system.user.create` |

**菜单种子对账**：`DataInitializer` 对每个种子按固定 `menu_id` 做 **upsert**（已存在则回写 `menu_name` / `route_path` / `component` / `perm_code` / `icon` / `sort_no`），并在同一次运行里软删"`route_path` 或 `perm_code` 与种子冲突但 ID 不在种子内"的历史残留行，避免升级后出现重复菜单。

## 3. 角色与权限

不做多租户。多用户、多角色、多菜单权限，以**角色**为单位授权，数据归属以**用户**为单位隔离。

### 3.1 数据归属模型

- 用户拥有的业务表（会话、运行、事件、技能、文件、知识库、知识库文档、创作文档、文档版本、提示词）带 `user_id varchar(40)`，行级隔离；会话消息与知识库分块不落库表，分别存 Manticore 表（`lw_msg`、`lw_kbchunk`），同样带 `user_id` 过滤。
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
- **特权划分**：角色管理（`system:role:*`）是特权来源，仅 `super_admin` 持有；`admin` 持系统管理与工作台全部功能但不含角色管理。赋权守门：给用户分配角色、给角色授菜单时，被授出的权限点必须是操作人已持有的权限的子集，杜绝横向提拔与纵向越权。
- 前端菜单与按钮由 `sys_menu` 驱动，接口返回当前用户可见菜单树与权限标识集合。

### 3.3 数据归属校验

访问单个资源时，先按 `(资源 ID, user_id)` 查；查不到即 `RESOURCE_NOT_FOUND`，不泄露他人资源是否存在。

### 3.4 登录安全

- **密码绝不明文过网**：登录、自助改密、管理员建用户、管理员重置密码四个入口的密码字段一律为 RSA 密文（`RSA/ECB/PKCS1Padding`，与浏览器端 JSEncrypt 对齐），公钥由 `GET /api/v1/auth/public-key` 下发（SPKI 的 Base64，进程内存持有，按 6 小时或 1000 次调用轮换）。解密失败即 `CREDENTIAL_INVALID`（自助改密与管理员设密为 `PAYLOAD_DECRYPT_FAILED`），不区分"密文坏"与"密码错"。
- **密文字段不做长度校验**：RSA-2048 密文 Base64 后 344 字符，`@Size` 加在密文字段上必然误判；口令强度一律在服务端解密后的明文上判定。
- **凭据绝不回显**：页面、接口响应、运行日志一律不得出现初始密码或明文密码；明文口令只允许出现在部署编排文件（`deploy/docker-compose.app.yaml` 给的默认值，供私有部署直接登录）。种子账号口令来自环境变量 `LITEWORKBENCH_ADMIN_PASSWORD`；缺失时随机生成并仅在启动日志 WARN 打印一次，同时置 `sys_user.pwd_reset_required = true`。
- **未激活账号可被配置项接管**：种子 `admin` 仍 `pwd_reset_required = true` 时，启动把口令对齐到配置值；账号完成改密后配置项不再覆盖。
- **首登强制改密**：`pwd_reset_required` 为 true 的用户，登录后只允许调用 `/auth/password` 与 `/auth/current`，其余业务接口返回 `PASSWORD_RESET_REQUIRED`。
- **失败计数**：同一用户名连续失败 5 次锁定 10 分钟（Redis 计数），登录接口另有按 IP 的 `@RateLimit`。

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

- 切面实现：`Redis SETNX lw:idem:{prefix}:{userId}:{参数摘要}`，命中即抛 `REPEAT_SUBMIT` 并通过响应头 `X-Lw-Idem-Key` 回传占位键；TTL 到期自动释放。
- 接口限流用 `@RateLimit` + 切面：`Redis 固定窗口 lw:rl:{prefix}:{主体}:{窗口号}`，登录按 IP 每分钟 10 次、流式对话按用户每分钟 20 次，超限抛 `RATE_LIMITED`。
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
| `KB_` | 知识库 |
| `KBDOC_` | 知识库文档 |
| `KBCHUNK_` | 知识库分块（Manticore） |
| `DOC_` | 创作文档 |
| `DOCV_` | 创作文档版本 |
| `PRPT_` | 提示词 |
| `MEMO_` | 长期记忆 |
| `LOGD_` | 日志文档（Manticore） |

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
| 线程池 | `ThreadUtil.newFixedExecutor`、`ExecutorBuilder` | 各处 `new Thread` |

**Hutool 不提供 ULID**：`IdUtil` 只有 UUID / ObjectId / Snowflake / NanoId，没有 ULID 实现，也不能拿 `fastUUID` 当有序 ID 用。业务 ID 一律 `IdGenerator`，禁止直接调 `IdUtil` 或 `UlidCreator`。

## 9. 日志约定

- 长链路、复杂流程必须打点：`开始 → 关键分支 → 结束`，带耗时与业务 ID。
- **级别只允许 `info` / `warn` / `error`**，禁止 `debug` / `trace`；排查需要的细节靠 `info` 打全字段，不靠调级别。
- 使用 `slf4j` + `lombok.@Slf4j`（或 `@Log4j2`），禁止 `System.out` 与 `printStackTrace`。
- 日志按天 + 按级别滚动，共三个文件：`liteworkbench.log`（全量 INFO+）、`liteworkbench-error.log`（WARN/ERROR 单独）、`liteworkbench-agent.log`（Agent 链路专用），保留 30 天；目录开发期默认 `logs/`，容器内用 `LITEWORKBENCH_LOG_DIR` 覆盖。
- **每条日志必须携带链路标识（MDC）**：请求进入即注入 `traceId`；已登录请求注入 `userId`；运行期日志另注入 `sessionId` / `runId`。四者同时落 Logback pattern 与 Manticore `lw_log` 的过滤列，禁止业务代码手工拼接这些字段。
- **日志检索按角色隔离**：持有 `monitor:log:global` 的角色（管理员）看全局日志；其余用户只能检索 `user_id = 自己` 的日志，约束由服务端强制注入，前端不做假设。
- 业务日志同时异步投递到 Manticore，供页面检索。
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
| 按钮 | 一律使用 `components/ui/LwButton.vue`，实现五态（见 DESIGN.md）；Element Plus 自带按钮由 `styles/index.css` 全局覆盖为同等五态 |
| 国际化 | 文案一律走 `vue-i18n`，组件内不写中文硬编码；菜单标签必须经 `utils/menu.ts` 的 `menuLabel()` 渲染，翻译缺失时降级为 key 末段的人类可读形式，绝不裸显 i18n key |
| 状态 | 每个异步操作必须有 loading / error / empty 三态 |
| 反馈 | 任何超过 300ms 的等待都要有骨架或 loading；任何状态变化都要有过渡动画或颜色变化 |
| 密码 | 密码字段一律 `type="password"` 且提交前用 `utils/crypto.ts` 加密，禁止明文进 store 持久化 |
| 路由 | 动态菜单由后端 `sys_menu` 下发，路由守卫校验登录态与权限标识，权限不足跳 `/403` |
| 权限 | 按钮级权限用 `usePermission().hasPerm()` 或 `v-perm` 指令判断，权限码与后端 `PermConst` 严格一致 |
| 分段切换 | 视图 / 时间 / 范围切换统一用 `components/ui/LwSegmented.vue`（五态），选中态浅紫底 + 主紫字 + 轻阴影，禁止各页自造 |
| 对齐 | 同行控件等高（默认档 h-10），筛选区不混排尺寸；表单标签统一左对齐同宽 |
| 错误页 | `403` / `404` / `500` 三个独立页面；无权限访问业务路由跳 `/403` 并说明所需权限 |

## 13. 代码风格与注释

格式统一：Java 4 空格缩进、行长 120；TypeScript 2 空格、单引号、行尾不写分号。提交信息 `feat(scope): 描述`，scope 取模块名。

注释只写"是什么"和"边界"，"为什么"一律归文档。注释越短越好：**文件头一行、方法一行签名说明、字段有歧义才有注释**。

| 位置 | 要求 |
|---|---|
| 文件头 | 一行说明文件职责，禁止多行设计说明与背景叙述 |
| 方法 | 一行签名说明，禁止多行 `@param` / `@return` 块；getter / setter / 无参构造不写 |
| 字段 | 有歧义才有注释；`entity` / `dto` / `vo` 的业务字段必须有，同名字段（`userId`）不必 |
| 常量 | 保留一行用途说明，**单位与阈值必须写**（秒 / 毫秒 / 字符数 / 条数） |
| 行内 | 禁止连续注释块与装饰性分隔线，需要解释时压成一行 |
| 设计理由 | 不写进代码，写进 `specs/` 对应文档 |

以下四类注释即使费字也**必须保留**，只允许压缩措辞：

| 类别 | 例子 |
|---|---|
| 协议与外部约束 | PG 不做隐式转换，写入 `jsonb` 必须 `CAST` |
| 并发与时序前提 | `settled` 置位后 `push` 不再放行；先冲刷攒批再置终态 |
| 事务与安全边界 | 必须与主记录同事务写入；`user_id` 由服务端强制注入 |
| 单位与量纲 | 超时单位秒、节流单位毫秒、上限为字符数 |

## 14. 测试约定

只保留覆盖长链路、复杂场景与跨模块契约的测试；简单断言（ID 格式、字符串清洗、空参校验、SSE 帧文本）、纯算法与薄 mock 单元测试一律不写。当前共 2 个文件 5 个用例，随 `mvn test` 一次跑完（约 1 秒），全部不依赖中间件与 Spring 容器。

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `service/RunEventPipelineTest` | 2 | 事件长链路：一次完整运行的归一与合帧（增量合并边界、类型切换保序、工具起止与结果、用量入账）＋ 边界路径（超长裁剪、失败工具标记、异常事件） |
| `config/ContractTest` | 3 | 三处静态契约：权限点在三处声明的一致性（扫 api 包注解 + `PermConst` + 菜单种子）、Mapper XML 与接口的绑定与解析、路由兜底的顺序与命中范围（未命中 `/api` 回 404 信封、深链回落 `index.html`、带点静态资源不被吞） |

用例名用中文直述业务断言；一个用例串起一条完整链路或一个契约；契约测试集中在 `config/ContractTest`，不按契约拆多个文件。资源扫描一律走 `classpath*:` 模式，禁止用目录列举（打包成 JAR 后失效）；前端不写快照测试与纯渲染测试。

## 15. 依赖使用约定

依赖只取其长处，能力缺口自研补齐。以下约定为强制项，违反即返工。

### 15.1 Agent 内核与模型出站

内核全部自研并写在 `agent` 包内，无第三方 Agent 框架依赖。

| 约定 | 原因 |
|---|---|
| 配置层复用 hutool-ai 的 `AIConfig` 契约：`ProviderAIConfig` 承载 `apiUrl` / `apiKey` / `model` / 连接与读超时 / 动态参数 | 一处配置，内核与同步路径共用 |
| 同步文本路径（`ModelCaller`）优先走 hutool-ai 的原生服务，失败或空文本再回退 `agent/model` 的客户端 | 复用现成实现，同时保证异常路径可用 |
| 协议集合固定为五家：`openai` / `deepseek` / `doubao` / `grok` 走 OpenAI 兼容通道，`ollama` 走本地通道；默认端点与默认模型取 hutool-ai `XxxConfig` 的常量，不自行发明；未列入的协议一律不做 | 前后端协议集合一处定义，新增协议必须有人维护请求体与增量解析 |
| 流式出站与工具调用自研：`agent/model` 的 `OpenAiCompatClient` / `OllamaClient` 共用 `HttpModelSupport` | 需要逐行剥协议前缀、累积工具调用增量、按 `usage` 入账，并且取消必须立即关闭连接 |
| 工具调用请求体、消息模型、`usage` 与 `finish_reason` 解析全部自研 | 依赖只返回裸响应体，没有对应的 POJO |

### 15.2 Hutool 与 Sa-Token

| 约定 | 原因 |
|---|---|
| 只引 `hutool-all`（5.8.46，已含 AI 模块），不再单独声明子模块 | 聚合包已包含所需模块 |
| 业务 ID 一律走 `common.IdGenerator`，禁止 `IdUtil` / `UlidCreator` | Hutool 不提供 ULID，业务 ID 需带业务前缀且字典序近似时间序 |
| 集合工具统一用 `CollUtil` | `CollectionUtil` 只是它的别名 |
| 登录态存 Redis，令牌放 `Authorization` 头（裸令牌），权限码由 `config/StpInterfaceImpl` 经 Redis 缓存回源数据库解析（角色码取登录会话快照） | 不从请求体采信用户身份 |

### 15.3 Manticore

应用走 HTTP JSON API（`manticoresearch` SDK 10.2.0，服务端 `25.0.0`），MySQL 协议端口仅供人工排障。

| 约定 | 原因 |
|---|---|
| 建表（`CREATE TABLE IF NOT EXISTS`）走 `/cli`，且必须以 text/plain 裸 body 提交 | 表单编码会把引号与换行转义，该端点不接受转义形式，语句会静默失败 |
| 建表必须显式 `charset_table='non_cont,cjk'` 与 `morphology='icu_chinese'`，文本列写 `text indexed stored` | 默认 charset_table 下文本不入索引；只 `stored` 不索引则全文检索无结果 |
| 建表后必须用属性过滤 SELECT 复验表与列类型 | DDL 失败也回 `Query OK`，且 `/insert` 遇到不存在的表会按文本类型隐式建表，会掩盖失败 |
| 检索与聚合走 `/search`、带 `FROM <索引>` 的 `/sql`；健康探测同理 | 非 raw 模式下 `/sql` 把语句编译成搜索查询，没有 `FROM` 的 `SELECT 1` / `SHOW TABLES` 会被回 501 |
| 范围过滤必须传 `Map.of("ts", Map.of("gte", x))` 形式 | 缺字段名包裹会被序列化成非法过滤器 |
| 幂等写用「先删同键再插入」，删除结果按 `DeleteResponse.deleted` 对账 | 文档数字 ID 由引擎分配，业务主键是 string 列 |
| 日志保留由应用定时任务删除，不依赖引擎 TTL | Manticore 无文档级 TTL |
| `9308` 不发布宿主机，容器同网络用服务名直连 | 减少暴露面 |

## 16. 运行参数约定

云主机规格小，容器启动参数固定以下一组（见 `deploy/Dockerfile`）：

| 参数 | 值 | 理由 |
|---|---|---|
| `-XX:+UseG1GC -XX:MaxGCPauseMillis=200` | — | 小堆下停顿可控 |
| `-XX:MaxRAMPercentage=70` | 70% | 给堆外与 PageCache 留空间 |
| `-XX:+UseStringDeduplication` | — | 日志与事件文本重复率高 |
| `-XX:+UseCompressedOops -XX:+UseCompressedClassPointers` | — | 指针与对象头压缩，省内存 |
| `-Xss512k` | — | 线程栈从默认 1m 降到 512k，支撑更多 SSE 线程 |
| `-XX:+ExitOnOutOfMemoryError` | — | OOM 直接退出，交给编排重启，不留半死状态 |

## 17. 并发与线程池治理

单个容器跑全部异步能力，**禁止在业务类里裸 `Executors.newXxx()`**。所有线程资源由 `config/ExecutorConfig` 一处声明，按用途分池并各自命名，业务只做构造注入。

| 池用途 | Bean 方法 | 规模策略 | 拒绝策略 | 理由 |
|---|---|---|---|---|
| Agent 运行编排 | `lwRunExecutor` | CPU 核数 × 2，队列 256 | `CallerRunsPolicy` | 提交方降速而不是丢任务；运行丢任务是直接可见的业务损失 |
| 工具执行 | `lwToolExecutor` | CPU 核数 × 4，队列 512 | `AbortPolicy` + 结构化失败 | 工具超量说明模型在打转，快速失败让内核收敛 |
| 事件扇出与日志投递 | `lwEventExecutor` | 固定 4，队列 2048 | 丢弃 + WARN 计数 | 观测链路不得反压业务链路；丢弃必须留下计数便于发现 |
| 知识库解析入库 | `lwIndexExecutor` | CPU 核数，队列 128 | `CallerRunsPolicy` | 大文档解析慢，宁可使请求线程变慢也不丢用户以为已上传的文档 |
| 心跳、超时巡检、日志清理 | `lwScheduleExecutor` | 固定 2 | `AbortPolicy` | 只跑短周期内部任务 |

规则：

- 统一用 Hutool `ExecutorBuilder` / `ThreadFactoryBuilder` 构造，线程名前缀 `lw-<用途>-`，排障时线程栈可读。
- 所有池必须注册优雅关闭（`DisposableBean` 或 `@PreDestroy`），按 `shutdown → awaitTermination(10s) → shutdownNow` 三段式收尾；容器停机不留悬挂 SSE 连接与子进程。
- **阻塞 IO（PG / Manticore / MinIO / RabbitMQ）不得占用运行编排池**；`@Async` 必须显式指定 `value = "lwXxxExecutor"`，不带参的 `@Async` 跑在 Spring 默认池上，视为违规。
- 有界一切：队列必须给容量，禁止 `LinkedBlockingQueue` 无参构造（无界队列在 2GB 容器里等价于延迟 OOM）。
- 信号量在**提交任务前**获取，不在任务线程内获取；任务内获取会让并发度真实上界等于调用数。
- `LoginContext` 基于 `ThreadLocal`，跨池必须显式把 `userId` 作为首个业务参数传递（同 §3.1），禁止依赖上下文传播。

## 18. 不变量表

以下每条都是「违反即出现难以定位故障」的约束，修改相关代码前先读，改完自问是否违反。

| 不变量 | 锁死原因 |
|---|---|
| 同一会话的事件出口必须串行化，且按会话分道而非全局一把锁 | 并行工具在各自线程 emit，并发改同一份累计位点会让 assistant 文本丢字或重影；全局锁则让一个慢订阅者堵住所有会话 |
| 「分配 seq」与「投递 SSE」必须在同一次持锁内完成 | 分两步时 seq 大的帧可能先到，前端按 seq 去重会把先到的新帧当旧帧丢掉，表现为「消息少了后半段」 |
| toolResult 严格按 assistant 的 tool_calls 顺序回填 | OpenAI 兼容协议靠下标配对，错位会被上游判 400，整轮作废 |
| 工具实现必须捕获 `Throwable` 并转成结构化失败结果 | 单个工具抛异常会顺着 CompletableFuture 吞掉整次运行，用户只看到「没有输出」且无堆栈 |
| token 累积一律 `StringBuilder`，禁止循环内字符串 `+=` | 每 token 复制整段正文，长回答末尾退化为 O(n²)，表现为「越往后越卡」 |
| 中间帧只带增量，累积正文只在收尾帧给全文；丢帧检测靠恒定大小的水位快照，200ms 节流 | 每帧附带完整累积正文会让长回答退化为每帧 O(n)，SSE 带宽与序列化双双爆炸；水位快照只带 seq 与长度，代价恒定 |
| 终端事件（`run.completed` / `run.failed` / `run.aborted`）不允许静默丢弃 | 丢中心事件会让前端永远停在「运行中」，且无任何报错可追；缓冲区满应先挤增量帧，仍放不下则断连并发 gap 帧让前端拉快照对账 |
| Python 脚本必须落临时文件再把文件名传给解释器 | 拼 `-c "..."` 在代码含换行或中文路径时必炸，且 Windows 下还会被 shell 引号规则改写 |
| 取消或超时必须杀进程树，不能只杀直接子进程 | Python 起的 multiprocessing 子进程会变孤儿持续吃 CPU，容器里没有 reaper 回收 |
| 子进程 PATH 首位必须是内置解释器目录，并强制 UTF-8 | 服务器上装着另一个 Python 时 `pip install` 会装到系统环境，重启后内置运行时 ModuleNotFound；不强制编码时中文输出乱码且不可复现 |
| 工具输出必须 sanitize 后入库与下发 | 一个 ANSI 转义序列就能打崩整条 SSE 帧或 JSON 序列化，且只在有色输出时出现，极难复现 |
| 同一工具同一参数在一次 run 内累计第 4 次直接判失败 | 模型卡在同一调用上打转比撞轮数上限更难排查；计数不衰减，被策略拒绝的调用同样计一次，否则一张审批卡能无限重发 |
| 失败必须回滚本轮追加的上下文 | 先记下 `contextSize`，异常时弹回到该位置；不回滚会让半截消息污染下一轮，模型看到不完整的工具结果继续推理 |
| 幂等键必须包含 userId | 只含参数摘要时，不同用户提交同样内容会互相排掉，表现为「别人点了我就失败」 |

## 19. Agent 内核实现约定

内核全部在 `agent` 包，不引任何 Agent 框架；同步文本路径复用 hutool-ai（见 §15.1），流式与编排自研。

### 19.1 Session 与 Run 是二级粒度

`Session`（长生命周期，承载上下文）与 `Run`（一次 turn：prompt → assistant 工作 → 结算）严格区分。一个 Session 产生多个 Run，Run 绝不替代 Session。用量、耗时、产物、诊断全部挂在 Run 上，Session 只负责上下文叠加与归属。

| 表 | 承载 |
|---|---|
| `wb_session` | 标题、默认模型、归属用户、最后活跃时间 |
| `wb_run` | `relation`（original / retry / recovery / rerun）、`parent_run_id`、`state`、用量、耗时、轮数、终态原因 |
| `wb_event` | 归一后的事件流，带 `seq`，一次 Run 内单调递增 |

没有 Run 这一层，「这一次回答花了多少 token、卡在第几轮」永远答不上来。

### 19.2 事件协议

事件分两层：内核侧 `AgentEvent` + `AgentEventType` 是**封闭枚举**，工作台侧 `RunEventDTO` + `EventTypeEnum` 是对外的**点分命名**（`run.*` / `text.*` / `thinking.*` / `tool.*` / `model.*` / `plan.*`）。

- 内核类型不做 `default` 兜底：归一 switch 穷举全部类型，新增内核事件必须在适配器补分支，由编译器保证不漏。
- 入口只有一处：内核对 `AgentEvent` 交给 `AgentEventAdapter` 适配成 `RunEventDTO` 并分配 `seq`。
- **单一账本**：用量只在 `MODEL_CALL_END` 入账一次，Service 禁止手写 UPDATE 累加——两套管账必然对不齐。
- 工具起止事件严格按下标分批发出（起 → 执行 → 止 → 结果），并发执行也不改变顺序，保证 assistant 的 `tool_calls` 与 tool 结果一一配对。
- 事件出进程前必须是裁剪过的 DTO：禁止把实体、内部句柄或未声明字段直接丢进 `SseEmitter`。
- 设计细节与完整事件表见 `specs/01-agent-core.md`。

### 19.3 取消语义

`abort` 与 `run.completed` 存在竞态，裁决规则固定为 **abort 赢**：已结算但未最终化的 Run 改写为 `aborted`。取消信号同时要：模型出站客户端立即关闭连接（`HttpModelSupport` 持有请求引用），工具子进程树立即 kill。

### 19.4 工具契约

工具走「POJO 实现类 + `@AgentTool` / `@AgentToolParam` 方法注解 + `ToolRegistry` 反射注册」，JSON Schema 由注册期反射生成，不自造 table-driven 清单——清单与实现会漂移。

| 注解属性 | 语义 |
|---|---|
| `risk` | `safe` / `confirm` / `danger`：`confirm` 及以上在页面手工调用时必须显式确认，Agent 路径由平台级启停统一闸门 |
| `parallelSafe` | 声明能否并发执行；批内任一为 false 则整批退化为串行，写类工具靠这条保证永不并发，无需额外锁 |
| `emitsPlan` | 输出是结构化计划，内核据此额外产出一条 `plan.updated`，界面不必解析工具输出文本 |

- 工具失败一律返回 `ERROR: ` 前缀的结构化文本而不是抛异常：模型能据此换路径，抛异常只会让整轮对话崩掉。
- 输出统一过 `OutputSanitizer`（剥控制字符、**保尾**截断——异常栈与结论在最后一行），并受 `wb_tool.enabled` 闸门约束。
- 重复调用护栏在 `AgentLoop`：同一（工具名 + 参数）在一次 run 内超限即返回结构化失败并提示换路径；计数不衰减。
- 工具池满时返回结构化失败而不是阻塞：池满说明并发已经到顶，让模型换路径比排队更安全。

### 19.5 规划器 / 长期记忆 / 子运行

三项能力都按「内核只认端口，规则在 service」落地，`agent` 包不出现 mapper。

| 能力 | 内核侧 | 规则归属 |
|---|---|---|
| 规划器 | `task_plan` 工具（`emitsPlan`）+ `PlanNormalizer` 解析行式清单 | 步数与单步长度上限在 `PlanConst`；计划以 `plan.updated` 覆盖式下发，界面不做增量合并 |
| 长期记忆 | `memory_write` / `memory_search` 工具 + `MemoryPort` | 容量闸门、同 key 覆盖、权重淘汰、注入条数与字符上限全部在 `MemoryService` |
| 子运行 | `task_delegate` 工具 + `SubRunPort` | `SubRunService` 落 `parent_run_id` + `relation=child`，同步等产出；中间帧不推送、收尾后一次性落库 |

- 记忆注入放在系统提示**末尾**：越靠近本轮输入越容易被遵守；只取权重最高的少量，避免挤占上下文。
- `task_delegate` 默认在 `wb_tool` 里 **disabled**：子运行会真实消耗 token，必须由管理员显式开启。
- 子任务描述必须自带全部上下文——子运行看不到当前对话，这是能力边界不是实现缺陷。

## 20. 内置 Python 运行时约定

容器内唯一脚本运行时是 Python（`apk add python3`），不嵌发行包、不做 venv、不做多版本管理——确定性来自镜像本身。

| 约定 | 理由 |
|---|---|
| 解释器探测四级回退：显式配置路径 → 数据目录内置解释器 `{data-dir}/runtime/python/bin/` → `PATH` 的 `python3` → `python` | 允许私有部署换路径，缺省情况开箱即用 |
| 探测结果进程内缓存，命中走 volatile 读不取锁，首次探测用双检锁串行化 | 每次工具调用都探测一遍，会把「首次启动等一会」变成「每次调用都卡一下」 |
| 脚本必须落地成临时文件，禁止拼 `-c "..."` | 见不变量表 |
| 执行必须带超时控制 + `ProcessHandle.descendants()` 递归 kill + `waitFor` 收尾窗口 | 见不变量表进程树条目 |
| 子进程环境必须把解释器所在目录置 `PATH` 首位并强制 UTF-8 | 脚本内 `pip install` 会装到系统环境，重启后内置运行时 ModuleNotFound |
| 超时按结构化失败回报（含已有输出与超时原因），不中断整次运行 | 模型能据此缩短脚本或分批处理，而不是只知道「不能用」 |
| 输出统一 UTF-8 解码 + `OutputSanitizer` 清洗 + 保尾截断（8000 字符） | 见不变量表 sanitize 条目 |
| 不可用只告警不阻断启动，失败原因写清并在健康检查中单独暴露 | 没有 Python 不影响除该类工具外的全部能力，不该整机起不来 |
