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

## 13. 注释规范

只写"是什么"和"边界"，"为什么"一律归文档。注释越短越好：**文件头一行、方法一行签名说明、字段有歧义才有注释**。

| 位置 | 要求 |
|---|---|
| 文件头 | 一行说明文件职责 |
| 方法 | 一行签名说明，禁止多行 `@param` / `@return` 块 |
| 字段 | 有歧义才有注释 |
| 行内 | 禁止连续注释块，需要解释时压成一行 |
| 设计理由 | 不写进代码，写进 `specs/` 对应文档 |

## 14. 测试约定

只保留覆盖长链路、复杂场景与跨模块契约的测试；简单断言（ID 格式、字符串清洗、空参校验、SSE 帧文本）、纯算法与薄 mock 单元测试一律不写。当前共 3 个文件 6 个用例，随 `mvn test` 一次跑完。

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `service/RunEventPipelineTest` | 2 | 事件长链路：一次完整运行的归一与合帧（增量合并边界、类型切换保序、工具起止与结果、用量入账）＋ 边界路径（超长裁剪、失败工具标记、异常事件） |
| `config/PermissionContractTest` | 1 | 权限契约：api 包控制器注解与 `PermConst` 声明的权限点全部存在于菜单种子（自动扫描 api 包，无需登记清单） |
| `config/WebRouteContractTest` | 3 | 路由契约：接口兜底与前端深链兜底的映射顺序与命中范围（未命中 `/api` 回 404 信封、深链回落 index.html、带点静态资源不被吞、两者模式重叠故顺序是唯一保障） |

用例名用中文直述业务断言；一个用例串起一条完整链路，一个文件只答一个契约问题；前端不写快照测试与纯渲染测试。

## 15. 依赖使用约定

依赖只取其长处，能力缺口自研补齐。以下约定为强制项，违反即返工。

### 15.1 Agent 内核与模型出站

Agent 内核全部写在 `agent` 包内，不引任何 Agent 框架。

| 约定 | 原因 |
|---|---|
| 配置层复用 hutool-ai 的 `AIConfig` 契约：`ProviderAIConfig` 承载 `apiUrl` / `apiKey` / `model` / 连接与读超时 / 动态参数 | 一处配置，内核与同步路径共用 |
| 同步文本路径（`ModelCaller`）优先走 hutool-ai 的原生服务，失败或空文本再回退自研客户端 | 复用现成实现，同时保证异常路径可用 |
| 供应方预设与 hutool-ai 对齐：`openai` / `deepseek` / `doubao` / `grok` 走 OpenAI 兼容通道，`ollama` 走本地通道；默认端点与默认模型取 hutool-ai `XxxConfig` 的常量，不自行发明；hutool-ai 未支持的协议（如 Anthropic）一律不做 | 依赖有什么用什么，前后端协议集合一处定义 |
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
