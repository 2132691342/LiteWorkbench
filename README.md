# LiteWorkbench

自托管的企业级个人 AI 创作工作台：多用户、多角色、多菜单权限。Agent 负责干活，工作台负责让人**看见并控制**它是怎么干的，并把产出沉淀为可复用的创作资产。

一个应用容器 + 五个中间件容器，对外只开一个端口。

---

## 1. 定位

LiteWorkbench 解决两件事：**Agent 跑起来之后看不见它在干什么**，**AI 聊完即忘、创作产出留不下来**。

它不是一个聊天界面，而是一个创作工作台：技能约束 Agent 的行为，知识库给它资料，工作台看它怎么干，创作台把结果落成文档与版本。

| 能力 | 说明 |
|---|---|
| 多角色 | 内置超级管理员 / 平台管理员 / 普通用户，支持自定义角色 |
| 用户与权限 | 用户 / 角色 / 菜单三段式 RBAC，按钮级权限点，Sa-Token 会话；用户支持批量启停与批量删除，逐条守门并回报失败原因 |
| 安全登录 | 密码 RSA 加密传输、失败锁定、首登强制改密、凭据零回显 |
| 会话工作台 | 多轮对话、流式输出、随时中断；一次对话产生一条可回放可审计的 Run |
| 运行可观测 | 运行记录页：时间线、工具链、链路瀑布、Run 树、用量归因、回放播放器 |
| 创作台 | Markdown / 富文本 / 代码三种文档，版本快照与回滚，AI 续写润色摘要扩写翻译（流式），全站全文检索 |
| 知识库 | 原件存 MinIO，分块存 Manticore，页面检索与 Agent 工具共用同一份索引 |
| 工具系统 | Python 执行、文件读写、HTTP 请求、知识库检索、任务规划、记忆读写、子任务委派，均带超时与输出裁剪 |
| 技能 | 两段式：摘要常驻提示词，命中触发词或输入里点名 `/code` 才展开正文，节省上下文；内置 13 个操作手册（先纲后文 / 取证引用 / 三遍润色 / 数据简报 / 长文摘要 / 小说创作 / 内容工坊 / 图像提示词 / 深度调研 / 营销文案 / 去 AI 味 / 学术翻译 / 开头钩子），可改可删可一键恢复默认 |
| 输入交互 | 工作台输入框支持 `/` 唤出技能与命令、`@` 引用知识库 / 文件 / 创作文档，被引用内容在运行前装配进系统提示 |
| 长期记忆 | Agent 跨会话记住的偏好与事实，按键覆盖，自动注入后续对话 |
| 文件空间 | MinIO 对象存储，按用户隔离前缀，支持在线预览、预签名下载与批量删除 |
| 工作区与存储桶 | 每个工作区绑定一个独立 MinIO 桶，会话与上传按工作区隔离；管理端可看全平台桶清单、建桶删桶，平台桶与工作区桶标记保护 |
| 日志中心 | 应用日志汇聚 Manticore（traceId / userId / sessionId / runId 全链路标识），管理员看全局、普通用户看本人，带分布概览与时间直方图 |
| 健康监控 | 数据库、Redis、Manticore、MinIO、RabbitMQ、Python 运行时逐项探测 |
| 调用观测 | SQL 与 HTTP 逐次调用的输入 / 输出 / 耗时审计，窗口内汇总与慢调用榜，明细进日志中心按 logger 检索 |
| 仪表盘 | 运行量、Token 消耗、成功率的指标卡，近 7 天趋势与工具分布图，快捷入口条与最近运行列表 |
| 组件库 | 设计规范的可视化样例：五态按钮、语义色、异步三态、图表与后台表格骨架件 |

### 明确不做

产品边界靠"不做什么"划清。以下各项不做，理由与替代路径一并固定，避免后续被单点需求带偏。

| 不做 | 不做的理由 | 替代 |
|---|---|---|
| 可视化编排画布 | 创作场景是"对话 + 文档"，线性步骤用一次对话即可表达；画布要背上执行器、图校验与版本迁移的长期成本 | 一次对话 + `task_plan` 行式清单 |
| 多智能体团队编排 | 编排复杂度与收益不成正比，且难以观测 | 一级子任务委派 `task_delegate`（默认停用） |
| 向量与 embedding 检索 | 一套全文设施已覆盖消息 / 知识块 / 日志 / 创作文档四处检索，引入向量要新增模型与重建流程 | Manticore 全文检索（pgvector 扩展已就位，暂不建向量列） |
| 断点续跑（Checkpoint 恢复） | 恢复语义要持久化模型上下文与工具副作用，边界难以收敛 | 事件流回放（只读、不重跑）+ 重新发起运行 |
| 运行内交互式审批 | 单用户工作台，挂起等待会占住运行线程且难 recover | 页面手工调用 `confirm` 级工具需 `confirmed: true`；Agent 内由平台级启停与风险分级兜底 |
| MCP / 插件市场 | 工具面由平台统一收口更好治理 | 内置 9 个工具 + `wb_tool` 平台级启停 |
| 多租户 | 定位是个人工作台，租户模型会拖累全部查询 | 共享库 + `user_id` 行级隔离 |
| 桌面端与裸机部署 | 只支持容器运行环境 | 单容器镜像，中间件另行编排 |

## 2. 设计取舍

只列贯穿全项目的决策，模块内部的取舍见 `specs/` 对应文档。

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 共享库 + `user_id` 行级隔离 | 单实例 Postgres 支撑多用户，不做多租户运维 | 查询必须显式带用户条件，靠规范与代码审查兜底 |
| 事件流作为运行态的唯一事实来源 | 运行可回放、可审计，前端只需一个归约器 | 事件量大，需要裁剪与批量写 |
| 会话上下文由消息索引显式组装，内核无状态 | 模型客户端可跨会话复用，会话状态只有一份 | 每轮要读一次历史消息 |
| 阻塞式 MVC + `SseEmitter` | MyBatis / JDBC / 子进程都是阻塞的，不需要来回切调度器 | 单实例并发受线程池限制 |
| 消息、知识块、日志、创作文档检索副本进 Manticore | 一套检索设施支撑四处全文场景，无需 embedding | 与 PG 双写，需按"以 PG 为准可重建"兜底 |
| 单容器内置 Python 运行时 | 用户无需自建执行环境，脚本能力开箱可用 | 解释器随镜像走，装包需落在数据卷 |
| 不做可视化编排画布 | 创作场景是"对话 + 文档"，线性步骤用一次对话表达，不为小众需求背上画布执行器与图校验的长期维护成本 | 固定多步流程需要拆成多次对话 |
| 不做微前端拆分 | 单仓单实例，路由由后端菜单驱动已足够 | 多团队并行交付时需再拆分 |
| 主键 = 业务前缀 + ULID | ID 自带类型信息，可分布式生成，字典序近似时间序 | 比雪花 ID 长，索引体积略大 |
| 不做运行级预算闸门（token / 成本 / 调用次数上限） | 单用户自托管，成本不外溢，闸门的调参成本高于收益 | 由 `loop.max-iters`、`loop.repeat-guard` 与工具超时三道兜底，超界即中止并落 `run.exceed` |
| 回放只读、不做断点续跑 | 事件流已是唯一事实来源，回放零副作用 | 恢复需重新发起运行，已消耗 token 不退 |

## 3. 技术栈

| 层 | 选型 | 版本 | 用途 |
|---|---|---|---|
| 运行时 | JDK | 17 | — |
| Web | Spring Boot Web | 3.5.12 | MVC + `SseEmitter` |
| 持久 | MyBatis Spring Boot Starter | 3.0.4 | SQL 映射，注解优先、XML 兜底 |
| 工具 | Hutool（含 `hutool-ai`） | 5.8.46 | 字符串 / 集合 / 日期 / 加解密 / 模型出站 |
| Agent | 自研内核 | — | 推理循环、工具注册、事件归一、规划、记忆、子运行，无第三方 Agent 框架依赖 |
| 模型出站 | `agent/model` | — | OpenAI 兼容通道（OpenAI / DeepSeek / Doubao / Grok），含增量解析、工具调用累积与取消 |
| 认证 | Sa-Token | 1.42.0 | 登录、权限注解、会话管理 |
| ID | ulid-creator | 5.1.0 | ULID 生成 |
| 数据库 | PostgreSQL（pgvector 镜像） | 16 | 业务数据；pgvector 扩展已就位，当前不建向量列 |
| 检索 | Manticore | 25.0.0（服务端）/ 10.2.0（Java SDK） | 消息 / 知识块 / 日志 / 创作文档全文检索 |
| 对象存储 | MinIO | 8.5.17（SDK） | 文件空间与知识库原件 |
| 消息 | RabbitMQ | 4 | 日志投递与知识库解析任务 |
| 前端 | Vue / TypeScript / Vite | 3.4.34 / 5.4.5 / 5.2.12 | — |
| 状态 / 路由 | Pinia / Vue Router | 2.1.7 / 4.3.2 | 状态与动态路由 |
| UI | Element Plus + 图标 | 2.9.0 / 2.3.1 | 基础控件 |
| 样式 | UnoCSS | 0.58.6 | 原子类 + 设计令牌 |
| 请求 | Axios | 1.6.8 | 唯一出口 `frontend/src/api/index.ts` |
| 图表 | ECharts | 5.5.0 | 仪表盘与日志中心可视化 |
| 富文本 | WangEditor | 5.1.23 | 创作台富文本模式 |
| 渲染 | markdown-it + highlight.js | 14.1.0 / 11.10.0 | 会话消息 Markdown 与代码高亮 |
| 预览 | vue-pdf-embed / docx-preview / XLSX | 2.1.7 / 0.4.1 / 0.18.5 | 文件空间在线预览 |
| 加密 | JSEncrypt | 3.3.2 | 登录密码 RSA 加密 |
| 国际化 | Vue I18n | 9.10.2 | 中英双语 |

## 4. 目录结构

```
LiteWorkbench/
├── backend/                     后端（Maven 单模块）
│   └── src/main/java/com/liteworkbench/
│       ├── api/                 控制器：参数校验、调用 service、封装响应
│       ├── service/             业务编排、事务边界、日志
│       ├── mapper/              数据库 / Manticore / Redis / MinIO 访问
│       ├── domain/
│       │   ├── entity/          实体映射（库表、Redis、Manticore）
│       │   ├── dto/             XxxREQ / XxxDTO
│       │   ├── vo/              XxxRESP / XxxVO
│       │   └── constant/        XxxEnum / XxxConst
│       ├── config/              配置类与 @ConfigurationProperties、线程池、路由兜底
│       ├── anno/ aspect/        自定义注解（幂等、限流）与切面实现
│       ├── mq/{producer,consumer}/  RabbitMQ 生产与消费
│       ├── agent/               自研 Agent 内核：模型协议、推理循环、工具、规划、事件归一
│       ├── runtime/             内置 Python 运行时的探测与执行
│       ├── observability/       日志汇聚、SQL 与 HTTP 调用观测、健康指标
│       └── common/              统一响应、异常、分页、ULID、登录上下文、加解密
│   └── src/test/java/       长链路事件测试与前后端契约测试（见 AGENTS.md §14）
│   └── src/main/resources/
│       ├── mapper/*.xml         仅动态 SQL 与长 SQL；单表增删改查走注解，Manticore 与检索类查询走 Java 实现不落 XML
│       ├── db/schema.sql        建表与索引，启动期幂等执行
│       ├── application.yaml     全部外部化配置的默认值
│       └── logback-spring.xml   日志滚动与落盘策略
├── frontend/                    前端
│   └── src/{api,views,components,stores,composables,directives,plugins,styles,locales,router,types,utils}
├── docs/                        项目级事实：架构、数据模型、接口契约、错误码、部署、开发
├── specs/                       子系统设计：一篇一个域，讲怎么实现与为什么
└── deploy/                      容器与编排（Dockerfile、应用编排、中间件编排、发布脚本）
```

后端目录的详细职责与依赖方向见 [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)；包命名与编码规则见 [AGENTS.md](AGENTS.md)。

## 5. 快速开始

### 5.1 中间件

```bash
cd deploy
./gen-secrets.sh                                    # 生成 .env.infra（字段清单见 .env.infra.example）
set -a && . ./.env.infra && set +a
podman-compose -f docker-compose.yaml up -d         # 或 docker compose -f docker-compose.yaml up -d
```

包含 PostgreSQL、Manticore、Redis、MinIO、RabbitMQ，五个中间件端口全部直接发布到宿主机，本机后端与运维工具可直接连（无需额外覆盖文件）。

### 5.2 后端

```bash
cd backend
mvn -DskipTests package
java -jar target/lite-workbench-server.jar
```

默认监听 `8080`，健康检查 `/actuator/health`。启动时建表、写入种子数据、创建 Manticore 索引，全部幂等。

### 5.3 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173，已把 /api 代理到 8080
npm run verify   # 类型检查 + 词条成对
npm run build    # = verify + 构建，产物在 frontend/dist
```

生产环境由应用容器直接托管前端产物；容器内固定 `8080`，对外宿主端口在 `deploy/deploy-app.sh` 单点定义（默认 `24399`）。

### 5.4 初始账号

首次启动写入 `admin`（超级管理员）与 `demo`（普通用户）两个种子账号。

编排文件里**不带任何默认口令**：未配置 `LITEWORKBENCH_ADMIN_PASSWORD` 时，应用在启动期随机生成初始口令并**仅在日志中 WARN 打印一次**，同时置 `pwd_reset_required = true`（首次登录强制改密，改密完成即失效）。页面、接口响应与运行日志中不出现任何明文口令。

想自己指定初始口令时，先改 `.env.infra` 里的 `LITEWORKBENCH_ADMIN_PASSWORD` 再启动；它只对尚未激活的种子账号生效。

登录与改密请求中的密码字段为 `RSA/ECB/PKCS1Padding` 密文（浏览器端 JSEncrypt），公钥由 `GET /api/v1/auth/public-key` 下发并定期轮换，明文密码不出浏览器。

## 6. 文档索引

| 文档 | 回答什么 |
|---|---|
| [AGENTS.md](AGENTS.md) | 工程强制规范：分层依赖、包与命名、API / ID / 字段 / 日志 / 配置约定、不变量、测试约定 |
| [DESIGN.md](DESIGN.md) | 前端视觉与交互规范：设计令牌、按钮五态、异步三态、页面范式、视觉验收、禁止清单 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 总体架构：分层与依赖方向、跨模块主链路、对外依赖边界、并发与性能 |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | 数据模型：PG 表结构与索引、Manticore 检索表、Redis 键、ID 与审计字段 |
| [docs/API-CONTRACT.md](docs/API-CONTRACT.md) | 接口契约：响应信封、各资源接口、SSE 帧结构、分页约定 |
| [docs/ERROR-CODES.md](docs/ERROR-CODES.md) | 错误码分段、每码语义与 HTTP 状态、异常处理约定 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 本地开发：前置条件、起服务、测试、常见改动入口、仓库内可执行资产 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 部署运维：拓扑、镜像、环境变量、升级回滚、备份、排障 |
| [specs/01-agent-core.md](specs/01-agent-core.md) | Agent 内核：推理循环、事件协议、工具契约、规划器、长期记忆、子运行、Python 运行时 |
| [specs/02-conversation.md](specs/02-conversation.md) | 会话与运行：状态机、并发控制、模型供应方与出站 |
| [specs/03-workspace-assets.md](specs/03-workspace-assets.md) | 工作台资产：技能与文件空间 |
| [specs/04-knowledge-doc.md](specs/04-knowledge-doc.md) | 知识库解析与检索、创作台与 AI 辅助写作 |
| [specs/05-observability.md](specs/05-observability.md) | 可观测：日志双写与检索、SQL 与 HTTP 调用观测、运行记录、链路瀑布、Run 树、用量归因、健康监控 |
| [specs/06-security.md](specs/06-security.md) | 安全：数据归属、RBAC 与赋权守门、登录安全 |
| [specs/07-frontend.md](specs/07-frontend.md) | 前端外壳：目录划分、动态路由、状态管理、关键流程 |

阅读顺序：**README（是什么）→ docs/ARCHITECTURE（整体分层与链路）→ specs（每个模块怎么实现、为什么、代价）→ AGENTS.md（写代码前查规范）→ DESIGN.md（写页面前查视觉）**。

## 7. 构建与交付

代码托管在 GitHub，`main` 为唯一主干。CI 由 GitHub Actions 执行三个 job：后端 `mvn package`（含测试）、前端类型检查与构建、以及仅在 `main` 推送时执行的发布——构建镜像推送到容器仓库，把应用编排与发布脚本同步到目标主机，再 SSH 执行 `deploy/deploy-app.sh` 完成拉取、重建、健康自检与失败回滚。

目标主机地址、账号与应用目录全部来自 GitHub Secrets；部署机密由目标主机上的 `deploy/.env.infra` 提供，CI 不注入任何口令。

中间件不随应用重建，由 `deploy/docker-compose.yaml` 单独维护，凭据同样走环境变量。详见 [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md)。

## 8. 约束

- 只支持容器运行环境，不做裸机与桌面端。
- 内置运行时只有 Python，不内置 Node / Go / Shell 发行版。
- 接口方法只用 GET 与 POST；路径参数一律放在末尾。
- 所有 ID 为 `业务前缀_ULID`，不使用雪花 ID 与 UUID。
- 用户拥有的业务数据带 `user_id`，查询必须显式携带用户条件。
