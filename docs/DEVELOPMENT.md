# DEVELOPMENT

本地环境、构建、测试、目录约定。

---

## 1. 定位

面向在本机把项目完整跑起来的人。只写现状，不写安装过程的排错流水。

## 2. 前置条件

| 工具 | 版本 | 说明 |
|---|---|---|
| JDK | 17.0.17 | 编译与运行后端 |
| Maven | 3.9.16 | 后端构建 |
| Node | 22.15 | 前端构建 |
| npm | 10.9 | 前端依赖管理 |
| Docker | 24+ | 起中间件 |

Maven 本地仓库默认 `~/.m2/repository`；若已有 `D:/MavenFiles`，用 `-Dmaven.repo.local=D:/MavenFiles` 复用缓存。

## 3. 目录约定

```
server/src/main/java/com/liteworkbench/
  api/ service/ mapper/ domain/{entity,dto,vo,constant}/
  config/ anno/ aspect/ mq/{producer,consumer}/ agent/ runtime/
  observability/ common/
server/src/main/resources/
  mapper/*.xml  db/schema.sql  logback-spring.xml  application.yaml

web/src/
  api/ components/{layout,ui,charts}/ composables/
  locales/ router/ stores/ styles/ types/ utils/ views/
```

新增一个子系统时，同步改动：

1. `domain/entity` 加实体，`resources/db/schema.sql` 加建表（用户拥有的业务表必须带 `user_id`）；
2. `mapper` 加接口与 XML（用户资源查询条件显式带 `user_id`）；
3. `service` 加编排；
4. `api` 加控制器（GET/POST、路径参数末尾、写接口加 `@Idempotent` 与 `@SaCheckPermission`）；
5. `specs/` 补一篇规格，`docs/API-CONTRACT.md` 补接口。

## 4. 后端

```bash
cd server
mvn -DskipTests package            # 打包
mvn spring-boot:run                # 直接运行
mvn test                           # 跑测试
```

启动依赖：PostgreSQL、Redisearch、MinIO、RabbitMQ 已就绪，或 `liteworkbench.degradation.*` 允许降级启动。

关键配置项（前缀 `liteworkbench.`，环境变量规则 `LITEWORKBENCH_<段>_<键>`）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 服务端口 |
| `liteworkbench.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | 数据库 |
| `liteworkbench.storage.endpoint` | `http://localhost:9000` | MinIO |
| `liteworkbench.agent.provider` | `openai` | 默认供应方 |
| `liteworkbench.agent.loop.max-iters` | 20 | 单轮最大迭代 |
| `liteworkbench.agent.model.default-name` | `qwen-plus` | 兜底模型名 |
| `liteworkbench.agent.skill.enabled` | true | 是否注入技能 |
| `liteworkbench.runtime.python.path` | 空 | 指定解释器路径，空则走探测 |
| `liteworkbench.runtime.data-dir` | `./data` | Python 工作目录与运行时数据的根 |
| `liteworkbench.auth.token-timeout` | 7200 | 登录态有效期（秒） |
| `liteworkbench.auth.default-password` | `admin123` | 种子账号初始密码 |
| `liteworkbench.security.secret` | 开发默认值 | 供应方密钥加密因子，生产必改 |
| `liteworkbench.degradation.allow-start` | true | 中间件不可用时是否允许降级启动 |

## 5. 前端

### 5.1 技术栈

| 领域 | 选型 |
|---|---|
| 核心 | Vue 3.4.34 + TypeScript 5.4.5 + Vite 5.2.12 |
| 状态 / 路由 | Pinia 2.1.7 + Vue Router 4.3.2 |
| UI | Element Plus 2.9.0 + UnoCSS 0.58.6 |
| HTTP | Axios 1.6.8（唯一出口 `src/api/index.ts`） |
| 可视化 | ECharts 5.5.0、AntV X6 2.18.1（预留流程图） |
| 工作流 | BPMN-JS 16.4.0（预留） |
| 富文本 | WangEditor 5.1.23、Mavon-Editor、CodeMirror 6（预留） |
| 文件处理 | XLSX、docx-preview、vue-pdf-embed（预留） |
| 微前端 | Qiankun 2.10.16（预留子应用挂载） |
| 国际化 | Vue I18n 9.10.2 |

### 5.2 命令

```bash
cd web
npm install
npm run dev        # 5173，/api 代理到 8080
npm run build      # 产物 web/dist
npm run typecheck  # vue-tsc --noEmit
```

环境变量 `.env.development` / `.env.production`：

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 接口基址，默认 `/api/v1` |
| `VITE_APP_TITLE` | 站点标题 |

## 6. 测试策略

只保留有针对性、覆盖复杂流程与长链路的测试，不写琐碎断言。

| 类型 | 文件 | 覆盖 |
|---|---|---|
| 单元 | `common/IdGeneratorTest` | ULID 格式、前缀、非法输入 |
| 单元 | `common/OutputSanitizerTest` | ANSI 与控制字符清理、保尾截断 |
| 单元 | `service/SkillServiceTest` | 触发词匹配、摘要常驻、数量上限、开关旁路 |
| 单元 | `service/RunEventFrameTest` | 推送合帧：同类型连续增量合并、类型切换不合并、seq 取尾 |
| 契约 | `agent/AgentEventAdapterTest` | SDK 事件 → 工作台事件的映射与字段、超长截断、未知事件归一 custom |
| 契约 | `api/SseFrameContractTest` | SSE 帧格式：`id` 为 seq、事件名 `run.event`、payload 为 JSON、心跳只发注释 |
| 契约 | `config/PermissionContractTest` | 控制器注解与 `PermConst` 的权限点全部存在于菜单种子 |

前端不写快照测试与纯渲染测试。

## 7. 代码风格

- Java：4 空格，行长 120，实体用 Lombok `@Data` / `@Builder`，构造注入。
- 注释：文件头一行，方法一行，连续注释块不超过 2 行。
- TypeScript：2 空格，单引号，行尾无分号，禁止 `any` 泛滥。
- 提交信息：`feat(scope): 描述`，scope 取模块名。

## 8. 常见改动入口

| 想改什么 | 改哪里 |
|---|---|
| 加一个工具 | `agent/WorkbenchTools.java` + `resources/db/schema.sql` 的 `wb_tool` 种子 |
| 加一个模型供应方协议 | `agent/ModelFactory.java` + `wb_provider` 记录 |
| 加一个权限点 | `config/DataInitializer.java` 的 `MENU_SEEDS`（`menu_type=button`）+ 控制器注解 |
| 加一个内置角色 | `domain/constant/RoleCodeConst.java` + `DataInitializer` 角色与授权 |
| 加一类日志检索字段 | `observability/RedisearchLogAppender.java` + `mapper/LogSearchMapper.java` 索引定义 |
| 加一个前端页面 | `web/src/views/` + `DataInitializer` 菜单种子（`component` 指向该文件） |
| 改配色 | `web/src/styles/tokens.css`（唯一色值来源） |
| 加一个写接口 | 控制器加 `@SaCheckPermission` + `@Idempotent` + `@PostMapping` |
