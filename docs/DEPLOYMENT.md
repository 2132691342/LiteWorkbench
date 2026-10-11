# DEPLOYMENT

容器部署、配置项、运维动作。只支持容器运行环境。

---

## 1. 定位

单实例容器部署。中间件由 `deploy/docker-compose.yaml` 提供，应用由 `deploy/Dockerfile` 构建。不做集群、不做滚动升级。开发服务器 `47.108.73.153` 部署同一套编排。

## 2. 拓扑

```
Browser ──► liteworkbench:24399（宿主机）→ 容器 8080（Spring Boot 内置静态资源 + REST/SSE）
                │
                ├─► postgres:5432      业务数据（含用户与权限）
                ├─► redisearch:6379    缓存 + 登录态 + 消息/知识块/日志索引
                ├─► minio:9000         文件对象 + 知识库文档原件
                └─► rabbitmq:5672      异步投递（日志索引、知识库解析）
```

生产环境前端静态资源由应用内置（Spring Boot `static/`），不单独部署 Nginx，减少一跳。

## 3. 中间件

云主机（Ubuntu 20.04，2GB 内存）运行时为 **Podman 3.4.2 + podman-compose**，无 Docker；本地开发可用 Docker 命令，语义一致。

```bash
podman-compose -f docker-compose.yaml up -d
```

| 服务 | 镜像（华为云 SWR） | 端口 | 凭据 |
|---|---|---|---|
| postgres | `pgvector/pgvector:pg16` | 5432 | postgres / admin_public |
| redisearch | `redislabs/redisearch:2.4.14` | 6379 | 密码 admin_public |
| minio | `pgsty/minio:RELEASE.2026-04-17` | 9000 / 9001 | minioadmin / minioadminpublic |
| rabbitmq | `rabbitmq:4-management` | 5672 / 15672 | admin / admin_public |

编排同时挂在 `postgres`、`redis`、`minio`、`rabbitmq` 四个服务上，容器崩溃自动重启。网络名为 `workbaby-infra_default`，应用容器必须加入同一网络才能按主机名互访。

### 3.1 共享中间件的所有权与禁改清单

中间件是**跨项目共享基础设施**（历史归属 workbaby-infra 项目），编排原件在主机 `/opt/workbaby-infra/docker-compose.yml`（只读参照），运行编排与数据卷在 `/root/workbaby-infra`（卷 `workbaby-infra_pgdata` / `workbaby-infra_minio_data`）。任何部署与运维操作必须遵守：

| 规则 | 说明 |
|---|---|
| 只碰自己的 | 允许操作的容器只有 `liteworkbench`，目录只有 `/opt/liteworkbench`；中间件容器一律不 stop / rm / restart |
| 网络禁止 rm | 严禁对 `workbaby-infra_default` 执行 `network rm`（Podman 会连带销毁挂载的容器）；需要改挂载只允许对单个容器 `network disconnect` + `network connect` |
| DNS 污染的安全修法 | aardvark-dns 残留记录导致主机名解析到旧 IP 时，对涉事容器逐个 disconnect + connect 重建记录，不动网络本身 |
| 新项目接入 | 要么作为 guest 加入 `workbaby-infra_default`（只加自己容器，不碰现有），要么用自己的编排开独立栈（独立网络 + 独立卷，端口错开）；绝不复用/修改共享栈的容器定义 |

## 4. 应用镜像

```bash
podman build -f deploy/Dockerfile -t liteworkbench:latest .
podman run -d --name liteworkbench -p 24399:8080 \
  --network workbaby-infra_default \
  --memory 1152m \
  -e LITEWORKBENCH_DATASOURCE_URL=jdbc:postgresql://postgres:5432/postgres \
  -e LITEWORKBENCH_DATASOURCE_PASSWORD=admin_public \
  -e LITEWORKBENCH_REDIS_HOST=redis \
  -e LITEWORKBENCH_REDIS_PASSWORD=admin_public \
  -e LITEWORKBENCH_STORAGE_ACCESS_KEY=minioadmin \
  -e LITEWORKBENCH_STORAGE_SECRET_KEY=minioadminpublic \
  -e LITEWORKBENCH_MQ_USERNAME=admin \
  -e LITEWORKBENCH_MQ_PASSWORD=admin_public \
  liteworkbench:latest
```

`--network` 指向中间件编排所在网络（`podman network ls` 查实际名称），使 `postgres`、`redis` 等主机名可解析。`--memory 1152m` 与编排内 `mem_limit` 一致：2GB 小主机上给中间件留足余量，JVM `MaxRAMPercentage=70` 据此收敛堆上限。构建为三阶段：Maven 编译后端 → Node 编译前端并拷入 `static/` → 运行时镜像基于指定 SWR 镜像 `openjdk:17-jdk-alpine`，容器内直接安装 python3 / curl / tzdata。

## 5. 配置项

全部可用环境变量覆盖，命名规则 `LITEWORKBENCH_<SECTION>_<KEY>`。

| 配置 | 环境变量 | 默认 |
|---|---|---|
| 数据源 URL | `LITEWORKBENCH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/postgres` |
| 数据源用户 | `LITEWORKBENCH_DATASOURCE_USERNAME` | postgres |
| 数据源密码 | `LITEWORKBENCH_DATASOURCE_PASSWORD` | — |
| Redis 主机 | `LITEWORKBENCH_REDIS_HOST` | localhost |
| Redis 密码 | `LITEWORKBENCH_REDIS_PASSWORD` | — |
| MinIO 端点 | `LITEWORKBENCH_STORAGE_ENDPOINT` | `http://localhost:9000` |
| MinIO Access Key | `LITEWORKBENCH_STORAGE_ACCESS_KEY` | minioadmin |
| MinIO Secret Key | `LITEWORKBENCH_STORAGE_SECRET_KEY` | — |
| RabbitMQ 主机 | `LITEWORKBENCH_MQ_HOST` | localhost |
| RabbitMQ 凭据 | `LITEWORKBENCH_MQ_USERNAME` / `LITEWORKBENCH_MQ_PASSWORD` | admin / — |
| 默认供应方 | `LITEWORKBENCH_AGENT_PROVIDER` | openai |
| 登录态有效期 | `LITEWORKBENCH_AUTH_TOKEN_TIMEOUT` | 7200 |
| 数据加密密钥 | `LITEWORKBENCH_SECURITY_SECRET` | — |
| 运行时数据目录 | `LITEWORKBENCH_RUNTIME_DATA_DIR` | `/var/lib/liteworkbench` |
| 降级启动开关 | `LITEWORKBENCH_DEGRADATION_ALLOW_START` | true |
| 日志目录 | `LITEWORKBENCH_LOG_DIR` | `/var/log/liteworkbench` |

密钥一律走环境变量，不落到配置文件，不进镜像层。

## 6. 初始化

首次启动自动执行 `resources/db/schema.sql`（`spring.sql.init.mode=always` 配合幂等 `CREATE TABLE IF NOT EXISTS`），并写入种子数据：

| 类型 | 内容 |
|---|---|
| 种子账号 | `admin`（超级管理员）、`demo`（普通用户），初始密码 `admin123` |
| 内置角色 | `super_admin`、`admin`、`user`（代码常量定义，不可删除改码） |
| 菜单树 | 仪表盘、工作台、创作台、提示词、工作流、文件、技能、知识库、日志、监控、设置、系统管理（含按钮权限点） |
| 内置工具 | Python 执行、文件读写、HTTP 请求、知识库检索（固定 ID 幂等重放） |
| 内置供应方 | `ollama` 本地供应方（无密钥，指向宿主机 Ollama） |
| 内置提示词 | 文章大纲、标题构思、润色改写、要点摘要、中英互译、代码讲解（固定 ID 幂等重放，只读） |

Redisearch 的 `idx:lw:log`、`idx:lw:msg`、`idx:lw:kbchunk`、`idx:lw:doc` 索引在应用启动时幂等创建。升级版本新增的菜单会自动补齐到 `super_admin` / `admin` / `user` 角色，无需手工重新授权。启动自检（`StartupGuardService`）会探测五大组件：核心组件（PostgreSQL / Redis）不可用且 `LITEWORKBENCH_DEGRADATION_ALLOW_START=false` 时终止启动，否则降级放行并打 warn。

## 7. 升级与回滚

| 动作 | 步骤 |
|---|---|
| 升级 | CI 推新镜像 → 主机执行 `deploy/deploy-app.sh <镜像>`（拉取、重建、就绪自检），schema 由幂等 DDL 自动补齐 |
| 回滚 | 用旧镜像 tag 再跑一次 `deploy-app.sh`；新增列不删除，保证旧版本可读 |
| 中间件升级 | 不随应用发布，单独改编排并重启对应服务 |
| 用户数据导出 | `pg_dump` 按 `user_id` 过滤导出 |

## 8. 健康检查

| 端点 | 用途 |
|---|---|
| `/actuator/health` | 存活探针 |
| `/actuator/health/readiness` | 就绪探针 |
| `/api/v1/monitor/health` | 页面展示的逐组件状态 |

容器 `HEALTHCHECK` 用 `curl -f http://localhost:8080/actuator/health`。

## 9. 日志与保留

| 文件 | 滚动 | 保留 |
|---|---|---|
| `liteworkbench.log` | 按天 | 30 天 |
| `liteworkbench-error.log` | 按天，仅 WARN/ERROR | 30 天 |
| `liteworkbench-agent.log` | 按天，Agent 链路专用 | 30 天 |
| Redisearch `idx:lw:log` | 按时间 | 30 天，启动时清理 |
| Redisearch `idx:lw:msg` / `idx:lw:kbchunk` | — | 永久，用户资产不过期 |

日志索引文档键带 30 天 TTL，过期由 Redis 自动回收；消息与知识块索引无 TTL。不做定时清理任务。

## 10. 备份

| 对象 | 方式 |
|---|---|
| PostgreSQL | `pg_dump` 每日一次，保留 7 份 |
| MinIO | `mc mirror` 到备份桶（对象键已含用户前缀，可按用户恢复） |
| Redis | 消息与知识块是用户资产：共享实例需开启 RDB 快照（`save 900 1` 级别）并落到持久卷；缓存类键丢了可重建 |
| 配置 | 环境变量清单入库外保管 |

## 11. JVM 参数

云主机规格小，容器固定以下一组参数（`deploy/Dockerfile` 的 `ENTRYPOINT`）：

```
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:MaxRAMPercentage=70
-XX:+UseStringDeduplication
-XX:+UseCompressedOops -XX:+UseCompressedClassPointers
-Xss512k
-XX:+ExitOnOutOfMemoryError
```

| 参数 | 理由 |
|---|---|
| `UseG1GC` + `MaxGCPauseMillis=200` | 小堆下停顿可控，无需 CMS/ZGC |
| `MaxRAMPercentage=70` | 给堆外内存与 PageCache 留位置，避免容器 OOM Kill |
| `UseStringDeduplication` | 日志与事件文本重复率高，去重省堆 |
| `UseCompressedOops` / `UseCompressedClassPointers` | 指针与对象头压缩，JDK17 默认开，此处显式声明防止被覆盖 |
| `Xss512k` | 线程栈从默认 1m 降到 512k，同等内存支撑更多 SSE 与工具线程 |
| `ExitOnOutOfMemoryError` | OOM 直接退出交给编排重启，不留半死状态 |

## 12. CI/CD

流水线定义在 `.github/workflows/ci.yml`，三个阶段串行：`backend`（编译 + 测试）→ `frontend`（类型检查 + 构建）→ `release`（仅 `main` 的 push 触发）。

发布动作：构建镜像推送到 GHCR（`:latest` 与 `:<sha>` 两个 tag），把仓库内 `deploy/docker-compose.app.yaml` 与 `deploy/deploy-app.sh` 同步到云主机 `47.108.73.153`（编排与发布脚本以仓库为唯一事实源），再 SSH 执行 `bash deploy-app.sh <镜像>`：拉取镜像、重建容器、清理同端口的 rootless DNAT 残留、按 `/actuator/health` 做就绪自检，未就绪即失败并打印容器与端口现场。

| Secret | 用途 |
|---|---|
| `SSH_HOST` | 云主机地址 |
| `SSH_USER` | 登录用户 |
| `SSH_KEY` | SSH 私钥 |
| `SSH_APP_DIR` | 主机上存放 `docker-compose.app.yaml` 的目录，当前值 `/opt/liteworkbench` |
| `LITEWORKBENCH_SECURITY_SECRET` | 应用密钥加解密因子（供应方 API Key AES 加密），32 位以上随机串 |
| `GITHUB_TOKEN` | 内置，用于登录 GHCR |

前置条件：仓库 Actions 需处于启用状态（Settings → Actions → Allow all actions），且四个 SSH Secret 配置齐全；流水线一次未跑或 secret 缺失时云主机不会有新镜像。GHCR 镜像跟随仓库可见性：仓库为 public 时镜像可匿名拉取，若改为 private 需在云主机先 `docker login ghcr.io`。

中间件不在 CI 中重建，只在主机上单独维护；应用与中间件靠 `liteworkbench_default` 网络互通。

## 13. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 静态资源内置到 jar | 少一个 Nginx 容器，部署简单 | 前端更新需重新打镜像 |
| 应用与中间件分成两个编排 | 发布应用不动数据库 | 需要跨 compose 共享网络 |
| 单实例 | 无分布式一致性问题 | 无法水平扩展，升级有短暂中断 |
| 启动自动建表 | 首次部署零手工步骤 | 需要 DDL 保持幂等 |
| 日志保留 30 天 | 存储可控 | 长周期审计需外部归档 |
| 堆上限 70% 容器内存 | 不易被 OOM Kill | 峰值时 GC 更频繁 |

## 14. 运维排障

### 14.1 分诊路径

```
现象
  ├─ 页面打不开 / 端口不可达
  │    → 宿主机 curl 127.0.0.1:24399/actuator/health；容器在跑却失败见场景处置
  ├─ 页面打不开 / 接口 500
  │    → 1 容器存活：podman ps + HEALTHCHECK 状态
  │    → 2 应用日志：liteworkbench-error.log 最近 50 行
  │    → 3 健康页：/api/v1/monitor/health（能登录时）
  ├─ 单个功能异常
  │    → 1 错误码分段定位领域（见 ERROR-CODES.md）
  │    → 2 日志中心按 traceId 检索（响应信封 requestId 即 traceId）
  └─ 运行行为异常（卡住 / 中断不了 / 输出不全）
       → 1 运行详情 + 事件回放（工作台右侧运行历史）
       → 2 agent 日志：liteworkbench-agent.log
```

### 14.2 场景处置

| 场景 | 判据 | 处置 |
|---|---|---|
| 容器反复重启 | HEALTHCHECK 失败、日志出现 OOM | 查 `podman stats` 是否超限，必要时调大容器内存 |
| 启动失败：缺配置 | 日志含环境变量名 | 补 `LITEWORKBENCH_SECURITY_SECRET` 等必填项；生产禁用开发默认密钥 |
| 启动失败：DDL 报错 | `spring.sql.init` 日志 | schema.sql 幂等，检查是否手工改过表结构导致类型冲突 |
| 全部接口 401 | 登录后仍 401 | Redis 被清空或重启，Sa-Token 会话丢失属预期，重新登录；检查 Redis 是否被写满 |
| 会话提交报 3002 `SESSION_BUSY` | 该会话有运行未收尾 | 等锁 TTL 自动释放，或对残留运行执行 abort |
| 运行中断不生效 | abort 后仍输出 | 看 `[run.abort]` 日志：有活动实例走 dispose，无实例则直接结算终态 |
| 模型调用 4002 / 4003 | 运行失败 | 供应方页面测试连通；核对 baseUrl（含 `/v1`）、模型名、密钥 |
| 无可用供应方 4001 | 对话直接失败 | 供应方列表为空或 `enabled=false`：补充配置并启用 |
| Python 7001 | 工具返回运行时未就绪 | 容器内确认 `python3 --version`，或用 `LITEWORKBENCH_RUNTIME_PYTHON_PATH` 指定，监控页重探 |
| 脚本越界 7004 | 工具返回不安全路径 | 脚本工作目录限定在数据目录内，确认脚本未引用目录之外的路径 |
| 文件 6002 / 6003 | 上传失败 / 超限 | 查 MinIO 健康与桶存在性；单文件上限 100MB |
| 知识库文档一直 pending | 状态不流转 | MQ 不可用或消费者掉线：看 `[kb.parse]` 日志，恢复后重解析 |
| 知识库 8604 / 8605 | 上传被拒 / 解析失败 | 8604 换支持的格式；8605 看文档行 `error_message`，修原件后重解析 |
| 消息或知识块消失 | 旧会话消息读不全 | 共享 Redis 的 `allkeys-lru` 会逐出无 TTL 的用户资产，改 `volatile-lru` 并调大 maxmemory，见 DATA-MODEL §7 |
| 日志中心查不到 | 检索为空 | MQ 降级时日志只落文件（记 warn）；索引启动幂等创建；文档 30 天 TTL 过期即不可查 |
| 事件回放缺尾部 | 回放不完整 | 事件按 100 条 / 200ms 攒批，断电可能丢最后一批；以会话助手消息为准 |
| 容器连不上中间件 | Connection refused 但中间件健康 | aardvark-dns 残留记录把主机名解析到旧 IP：对涉事容器逐个 `network disconnect` + `connect`；严禁对共享网络 `network rm` |
| 端口不可达但容器在跑 | 容器内 8080 返回 UP，宿主机 24399 失败 | rootless Podman 的 `CNI-HOSTPORT-DNAT` 残留指向已删容器 IP：删除该端口的 iptables 规则后重建，或重跑 `deploy-app.sh` |
| 磁盘紧张 | 容器占用上涨 | 日志含 30 天滚动与总量上限；Redis 256MB LRU；MinIO 按需清理 |

### 14.3 运维约束

- 排障不得打印密钥与令牌；需要核对密钥时只在供应方页面看脱敏值。
- 生产不开 debug 级日志，排查依赖 info 字段与 traceId 检索。
- 手工改库前先 `pg_dump` 对应表。
