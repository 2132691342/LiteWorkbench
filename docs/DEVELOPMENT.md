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
backend/src/main/java/com/liteworkbench/
  api/ service/ mapper/ domain/{entity,dto,vo,constant}/
  config/ anno/ aspect/ mq/{producer,consumer}/ agent/ runtime/
  observability/ common/
backend/src/main/resources/
  mapper/*.xml  db/schema.sql  logback-spring.xml  application.yaml

frontend/src/
  api/ components/{layout,ui,charts,settings}/ composables/
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
cd backend
mvn -DskipTests package            # 打包
mvn spring-boot:run                # 直接运行
mvn test                           # 跑测试
```

启动依赖：PostgreSQL、Manticore、Redis、MinIO、RabbitMQ 已就绪，或 `liteworkbench.degradation.*` 允许降级启动。

关键配置项（业务配置前缀 `liteworkbench.`，环境变量规则 `LITEWORKBENCH_<段>_<键>`；Spring 与 Sa-Token 原生键保留自身前缀）：

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 服务端口 |
| `spring.datasource.url` | `jdbc:postgresql://localhost:5432/postgres` | 数据库，环境变量 `LITEWORKBENCH_DATASOURCE_URL` |
| `liteworkbench.storage.endpoint` | `http://localhost:9000` | MinIO |
| `liteworkbench.manticore.url` | `http://localhost:9308` | Manticore HTTP JSON API |
| `liteworkbench.manticore.log-retention-days` | 30 | 日志表保留天数，每日定时清理 |
| `liteworkbench.agent.provider` | `openai` | 默认供应方，环境变量 `LITEWORKBENCH_AGENT_PROVIDER` |
| `liteworkbench.agent.loop.max-iters` | 20 | 单轮最大迭代 |
| `liteworkbench.agent.model.default-name` | `qwen-plus` | 兜底模型名 |
| `liteworkbench.agent.model.context-window` | 32000 | 上下文窗口 token 数，超窗口即压缩 |
| `liteworkbench.agent.model.reserve-tokens` | 4096 | 预留给模型输出的 token 数 |
| `liteworkbench.agent.model.sync-timeout-seconds` | 120 | 同步模型调用超时 |
| `liteworkbench.agent.model.stream-timeout-seconds` | 180 | 流式模型调用超时 |
| `liteworkbench.agent.history.limit` | 40 | 组装上下文时从 `lw_msg` 读取的历史消息条数上限 |
| `liteworkbench.agent.skill.enabled` | true | 是否注入技能 |
| `liteworkbench.kb.chunk.target-size` | 600 | 知识库分块目标字符数 |
| `liteworkbench.kb.chunk.overlap` | 120 | 相邻分块重叠字符数 |
| `liteworkbench.runtime.python.path` | 空 | 指定解释器路径，空则走探测 |
| `liteworkbench.runtime.data-dir` | `./data` | Python 工作目录与运行时数据的根 |
| `sa-token.timeout` | 7200 | 登录态有效期（秒），环境变量 `LITEWORKBENCH_AUTH_TOKEN_TIMEOUT` |
| `liteworkbench.auth.admin-password` | 空 | 种子账号初始密码，环境变量 `LITEWORKBENCH_ADMIN_PASSWORD`；留空则随机生成并仅打印一次 |
| `liteworkbench.security.secret` | 开发默认值 | 供应方密钥加密因子，生产必改 |
| `liteworkbench.degradation.allow-start` | true | 中间件不可用时是否允许降级启动 |

固定阈值不外部化，固化在 `domain/constant/*Const`（如 `KbConst.MAX_DOC_CHARS` 单文档提取正文上限、`KbConst.TOOL_OUTPUT_MAX` 工具输出保留长度、`AgentEventAdapter.TEXT_DELTA_MAX` 单帧增量截断长度）。

## 5. 前端

### 5.1 技术栈

| 领域 | 选型 |
|---|---|
| 核心 | Vue 3.4.34 + TypeScript 5.4.5 + Vite 5.2.12 |
| 状态 / 路由 | Pinia 2.1.7 + Vue Router 4.3.2 |
| UI | Element Plus 2.9.0 + UnoCSS 0.58.6 |
| HTTP | Axios 1.6.8（唯一出口 `src/api/index.ts`） |
| 可视化 | ECharts 5.5.0（仪表盘图表、日志中心分布与直方图） |
| 富文本 | WangEditor 5.1.23（创作台富文本模式） |
| 文件处理 | XLSX、docx-preview、vue-pdf-embed（文件空间在线预览，按需异步加载） |
| 国际化 | Vue I18n 9.10.2 |

### 5.2 命令

```bash
cd frontend
npm install
npm run dev        # 5173，/api 代理到 8080
npm run build      # 产物 frontend/dist
npm run typecheck  # vue-tsc --noEmit
```

环境变量 `.env.development` / `.env.production`：

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE_URL` | 接口基址，默认 `/api/v1` |
| `VITE_APP_TITLE` | 站点标题 |

## 6. 测试策略

只保留覆盖长链路、复杂场景与跨模块契约的测试：简单断言（ID 格式、字符串清洗、空参校验）、纯算法与薄 mock 单测一律不写，**共 3 个文件 4 个用例**，一次 `mvn test` 跑完（不到 1 秒）。

| 文件 | 用例 | 覆盖 |
|---|---|---|
| `service/RunEventPipelineTest` | 2 | 事件长链路：一次完整运行的归一与合帧（增量合并边界、类型切换保序、工具起止与结果、用量入账）＋ 边界路径（超长裁剪、失败工具标记、异常事件） |
| `api/SseFrameContractTest` | 1 | SSE 帧契约：业务帧带 `id=seq` 与固定事件名 `run.event`，心跳只发注释行 |
| `config/PermissionContractTest` | 1 | 权限契约：控制器注解与 `PermConst` 声明的权限点全部存在于菜单种子 |

约定：用例名用中文直述业务断言，一个用例串起一条完整链路，一个文件只答一个契约问题；前端不写快照测试与纯渲染测试。

## 7. 代码风格

格式约定：

- Java：4 空格，行长 120，实体用 Lombok `@Data` / `@Builder`，构造注入。
- TypeScript：2 空格，单引号，行尾无分号，禁止 `any` 泛滥。
- 提交信息：`feat(scope): 描述`，scope 取模块名。

命名、分层、注释、幂等、日志、错误码等**强制规则**全部在 [AGENTS.md](../AGENTS.md)，写代码前查阅，此处不复述。

## 8. 常见改动入口

| 想改什么 | 改哪里 |
|---|---|
| 加一个工具 | `agent/tool/WorkbenchTools.java` + `resources/db/schema.sql` 的 `wb_tool` 种子 |
| 加一个模型供应方协议 | `agent/model/` 新增 `XxxClient` + `agent/ModelFactory.java` 登记 + `wb_provider` 记录 |
| 改推理循环 | `agent/loop/AgentLoop.java`（纯逻辑，可离线单测） |
| 改 SSE 帧或事件归一 | `agent/AgentEventAdapter.java` + `api/SseRunSink.java` + `service/RunService.java` 的合帧与心跳 |
| 加一个权限点 | `config/DataInitializer.java` 的 `MENU_SEEDS`（`menu_type=button`）+ 控制器注解 |
| 加一个内置角色 | `domain/constant/RoleCodeConst.java` + `DataInitializer` 角色与授权 |
| 加一类日志检索字段 | `observability/ManticoreLogAppender.java` + `mapper/LogSearchMapper.java` 表定义 |
| 加一种知识库文档格式 | `service/DocTextExtractor.java` + `domain/constant/KbConst.java` 扩展名清单 |
| 加一个 AI 辅助动作 | `domain/constant/DocConst.java` 的 `ASSIST_TEMPLATES` + 前端动作下拉与文案 |
| 加一种在线预览格式 | `views/files/FileList.vue` 的 `kindOf` 与对应渲染分支 |
| 加一个前端页面 | `frontend/src/views/` + `DataInitializer` 菜单种子（`component` 指向该文件） |
| 改配色 | `frontend/src/styles/tokens.css`（唯一色值来源） |
| 加一个写接口 | 控制器加 `@SaCheckPermission` + `@Idempotent` + `@PostMapping` |

## 9. 脚本与流水线

仓库内的可执行资产只有下面五份，各自单一职责，不在别处再放临时脚本：

| 文件 | 职责 |
|---|---|
| `deploy/Dockerfile` | 三阶段构建：Maven 打包 → Vite 构建 → 运行时镜像（JRE + jar + 前端静态资源 + python3 / curl / tzdata） |
| `deploy/docker-compose.yaml` | 中间件编排：PostgreSQL(pgvector) / Manticore / Redis / MinIO / RabbitMQ，本机与云主机共用同一份 |
| `deploy/docker-compose.app.yaml` | 应用容器编排：单容器、限内存 1152m、接入中间件网络、对外端口 24399 |
| `deploy/deploy-app.sh` | 云主机侧发布：拉取镜像 → 删旧容器 → 重建 → 清理同端口转发残留 → 按健康端点就绪自检 |
| `.github/workflows/ci.yml` | 流水线：后端构建与测试 → 前端类型检查与构建 → 构建镜像推 GHCR → 同步编排与发布脚本 → SSH 执行发布 |

本机起中间件：

```bash
docker compose -f deploy/docker-compose.yaml up -d
```
