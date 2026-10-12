# LiteWorkbench

一个自托管的企业级个人 AI 创作工作台：多用户、多角色、多菜单权限。Agent 负责干活，工作台负责让你**看见并控制**它是怎么干的。

- 后端：JDK 17 + Spring Boot 3.5.12 + MyBatis 3.0.4 + Hutool 5.8.46（含 hutool-ai）+ Sa-Token 1.42.0 + ULID
- Agent：**自研内核**，模型出站只依赖 hutool-ai，OpenAI 兼容 / Anthropic / Ollama 三协议，不引任何 Agent 框架
- 前端：Vue 3.4.34 + TypeScript 5.4.5 + Vite 5.2.12 + Element Plus 2.9.0 + UnoCSS 0.58.6 + Pinia 2.1.7 + ECharts 5.5.0 + AntV X6 + WangEditor
- 存储：PostgreSQL 16（镜像内置 pgvector 扩展，预留）+ Manticore 25.0.0（全文检索）+ Redis 8 + MinIO + RabbitMQ
- 运行形态：容器，内置 Python 运行时，对外端口 `24399`
- 设计语言：紫色序列四色 `#F2F0F7 / #CBC9E2 / #9E9AC8 / #6A51A3`，极简风格，全站五态交互

---

## 1. 定位

LiteWorkbench 解决的是"Agent 跑起来之后我看不见它在干什么"的问题。

它不是一个聊天界面，而是一个**运行控制台**：一次对话会产生一条可回放、可中断、可审计的 Run；Run 由事件流构成，事件流落到存储里，前端按时间线还原。

| 能力 | 说明 |
|---|---|
| 多角色 | 内置超级管理员 / 平台管理员 / 普通用户三类角色，支持自定义角色 |
| 用户与权限 | 用户 / 角色 / 菜单三段式 RBAC，按钮级权限点，Sa-Token 会话 |
| 安全登录 | 密码 RSA 加密传输、失败锁定、首登强制改密、凭据零回显 |
| 会话工作台 | 多轮对话，流式输出，随时中断 |
| 运行可观测 | Run / Step / Event 三层时间线，工具调用输入输出可见 |
| 创作台 | 文档创作（Markdown / 富文本 / 代码）、版本快照与回滚、AI 续写润色摘要扩写翻译、全站全文检索 |
| 提示词库 | 内置模板 + 自定义模板，`{{变量}}` 渲染，一键应用到会话 |
| 工作流编排 | X6 画布编排 `llm` / `python` / `knowledge` / `tool` 节点，串行执行并逐步落事件 |
| 知识库 | 原件存 MinIO，切片分块存 Manticore，页面检索与 Agent 工具共用同一份索引 |
| 工具系统 | Python 运行时、文件读写、HTTP 请求、知识库检索，均带超时与输出裁剪 |
| 技能 | 存 `wb_skill` 表，两段式：摘要常驻提示词，命中触发词才展开正文，节省上下文 |
| 文件空间 | MinIO 对象存储，按用户隔离前缀，支持在线预览与预签名下载 |
| 日志中心 | 应用日志汇聚到 Manticore，页面支持按级别/关键字/时间检索 |
| 健康监控 | 数据库、Redis、Manticore、MinIO、RabbitMQ、Python 运行时逐项探测 |
| 仪表盘 | 运行量、Token 消耗、工具调用分布的可视化统计 |
| 组件库 | 设计规范的可视化样例，五态按钮、语义色、异步三态、图表全量可点 |

## 2. 为什么这样设计

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 共享库 + `user_id` 行级隔离 | 单实例 Postgres 即可支撑多用户，运维零增量 | 查询必须带用户条件，靠规范与代码审查兜底 |
| 事件流作为唯一事实来源 | 运行可回放、可审计、前端只需一个 reducer | 事件量大，需要裁剪与批量写 |
| Agent 无状态，上下文由 Manticore 消息表 `lw_msg` 显式组装 | 模型实例可跨会话复用，会话状态只有一份（索引） | 每轮要读一次历史消息 |
| 阻塞式 MVC + SseEmitter，不用 WebFlux | MyBatis/JDBC/进程执行都是阻塞的，不用来回切调度器 | 单实例并发受线程池限制 |
| 日志同时落文件与 Manticore | 排查有完整原文，检索有索引 | 写入路径变长，用异步队列兜底 |
| 知识库原件 MinIO + 分块 Manticore | 原件可重解析，检索零 embedding 成本 | 双处清理，靠级联删除保证一致 |
| 文档正文 PG + 索引 Manticore | 编辑强一致，全站检索复用同一套设施 | 双写，索引以 PG 为准可重建 |
| 用 X6 而不是 BPMN-JS | 画布结构即 JSON，与自有执行器直接对齐 | 不支持导入外部 BPMN 2.0 文件 |
| 不引 Qiankun 微前端 | 单仓单实例，路由由后端菜单驱动已足够 | 未来多团队并行交付时需再拆分 |
| 按业务前缀 + ULID 做主键 | ID 自带类型信息，分布式可生成，字典序近似时间序 | 比雪花 ID 长，索引体积略大 |
| Sa-Token 做认证鉴权 | 注解式权限、多种会话存储、与 Spring Boot 3 原生适配 | 登录态依赖 Redis，需保证缓存可用 |

## 3. 技术栈

| 层 | 选型 | 版本 | 用途 |
|---|---|---|---|
| 运行时 | JDK | 17.0.17 | — |
| Web | Spring Boot Web | 3.5.12 | MVC + SseEmitter |
| 持久 | MyBatis Spring Boot Starter | 3.0.4 | SQL 映射，XML 管理 |
| 工具 | Hutool | 5.8.46 | 字符串/集合/日期/加解密，含 `hutool-ai` 模型出站 |
| Agent | 自研内核 | — | 推理循环、工具注册、事件归一、上下文压缩、审批闸门 |
| 模型 | `agent/model` 三协议客户端 | — | OpenAI 兼容、Anthropic、Ollama（含 SSE 增量解析与取消） |
| 认证 | Sa-Token | 1.42.0 | 登录、权限注解、会话管理 |
| ID | ulid-creator | 5.1.0 | ULID 生成 |
| 数据库 | PostgreSQL + pgvector | 16 | 业务数据；pgvector 扩展预留，本期不建向量列 |
| 检索 | Manticore | 25.0.0 | 消息 / 知识块 / 日志 / 创作文档全文检索 |
| 对象存储 | MinIO | 8.5.17（SDK） | 文件空间 |
| 消息 | RabbitMQ | 4 | 异步任务与日志投递 |
| 前端 | Vue / Vite / TS | 3.4.34 / 5.2.12 / 5.4.5 | — |
| UI | Element Plus | 2.9.0 | 基础控件 |
| CSS | UnoCSS | 0.58.6 | 原子类 |
| 图表 | ECharts | 5.5.0 | 仪表盘可视化 |
| 画布 | AntV X6 | 2.18.1 | 工作流编排画布 |
| 富文本 | WangEditor | 5.1.23 | 创作台富文本模式 |
| 预览 | vue-pdf-embed / docx-preview / XLSX | 2.1.7 / 0.4.1 / 0.18.5 | 文件空间在线预览 |
| 国际化 | Vue I18n | 9.10.2 | 中英双语 |

## 4. 目录结构

```
LiteWorkbench/
├── backend/                     后端（Maven 单模块）
│   └── src/main/java/com/liteworkbench/
│       ├── api/                 控制器，只做参数校验与响应封装
│       ├── service/             业务流程编排
│       ├── mapper/              MyBatis / Manticore / Redis / MinIO 数据访问
│       ├── domain/
│       │   ├── entity/              实体（库表、Redis、Manticore 映射）
│       │   ├── dto/             XxxREQ / XxxDTO
│       │   ├── vo/              XxxRESP / XxxVO
│       │   └── constant/        XxxEnum / XxxConst（静态常量用接口）
│       ├── config/              配置类 + @ConfigurationProperties 内部类
│       ├── anno/                自定义注解（@Idempotent 等）
│       ├── aspect/              切面实现
│       ├── mq/{producer,consumer}/
│       ├── agent/               自研 Agent 内核：模型协议、推理循环、工具、事件归一
│       ├── runtime/             内置 Python 运行时
│       ├── observability/       日志汇聚、健康指标
│       └── common/              统一响应、异常、分页、ULID、登录上下文
│   └── src/main/resources/
│       ├── mapper/*.xml
│       ├── db/schema.sql
│       ├── application.yaml
│       └── logback-spring.xml
├── frontend/                    前端
│   └── src/{api,views,components,stores,composables,styles,locales,router,types,utils}
├── docs/                        项目级文档
├── specs/                       子系统规格
└── deploy/                      容器与编排
```

## 5. 快速开始

### 5.1 中间件

```bash
docker compose -f deploy/docker-compose.yaml up -d
```

包含 PostgreSQL、Manticore、Redis、MinIO、RabbitMQ（镜像取自华为云 SWR，国内网络友好）。

### 5.2 后端

```bash
cd backend
mvn -DskipTests package
java -jar target/lite-workbench-server.jar
```

默认监听 `8080`，健康检查 `/actuator/health`。

### 5.3 前端

```bash
cd frontend
npm install
npm run dev      # http://localhost:5173，已代理 /api 到 8080（生产对外端口 24399）
npm run build    # 产物在 frontend/dist
```

### 5.4 初始账号

首次启动自动建表并写入种子数据，内置 `admin`（超级管理员）与 `demo`（普通用户）两个账号。

**密码不在任何地方回显**：启动读取环境变量 `LITEWORKBENCH_ADMIN_PASSWORD` 作为初始密码；未设置时随机生成 16 位强密码并仅在启动日志中打印一次（WARN）。种子账号带 `pwd_reset_required` 标记，首次登录后必须立即改密才能使用其余功能。

```bash
export LITEWORKBENCH_ADMIN_PASSWORD='<your-strong-password>'
```

登录与改密请求中的密码字段为 RSA-OAEP(SHA-256) 密文，公钥由 `GET /api/v1/auth/public-key` 下发，明文密码不出浏览器。

## 6. 文档索引

| 文档 | 内容 |
|---|---|
| [AGENTS.md](AGENTS.md) | 工程规范，唯一权威：分层、命名、API 约定、错误码 |
| [DESIGN.md](DESIGN.md) | 视觉语言、设计令牌、交互状态机 |
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | 分层设计、模块职责、关键流程 |
| [docs/DATA-MODEL.md](docs/DATA-MODEL.md) | 库表、Manticore 检索表、ID 规范、审计字段 |
| [docs/API-CONTRACT.md](docs/API-CONTRACT.md) | REST 与 SSE 接口契约 |
| [docs/ERROR-CODES.md](docs/ERROR-CODES.md) | 错误码分段与处理约定 |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | 本地开发、构建、测试 |
| [docs/DEPLOYMENT.md](docs/DEPLOYMENT.md) | 容器部署、配置、运维与排障 |
| [specs/](specs/) | 子系统规格，一篇一个子系统，含模块设计与取舍 |

阅读顺序：**README（是什么） → ARCHITECTURE（怎么分层） → specs（每个子系统怎么实现） → AGENTS.md（写代码前查规范） → DESIGN.md（写页面前查视觉）**。

## 7. 构建与交付

代码托管在 GitHub（`2132691342/LiteWorkbench`），`main` 为唯一主干。CI 由 GitHub Actions 执行：后端 `mvn package`（含测试）、前端类型检查与构建，通过后构建镜像推送到容器仓库，再 SSH 到目标主机 `47.108.73.153` 执行 `deploy/deploy-app.sh` 完成发布与就绪自检。

中间件不随应用重建，由 `deploy/docker-compose.yaml` 单独维护，凭据走环境变量，不进仓库。

## 8. 约束

- 只支持容器运行环境，不做裸机与桌面端。
- 内置运行时只有 Python，不内置 Node / Go / Shell 发行版。
- 接口方法只用 GET 与 POST；路径参数一律放在末尾。
- 所有 ID 为 `业务前缀_ULID`，不使用雪花 ID 与 UUID。
- 用户拥有的业务表带 `user_id`，查询必须显式携带用户条件。
