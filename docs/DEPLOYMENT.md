# DEPLOYMENT

容器部署、配置项、运维动作。

---

## 1. 定位

本文回答：拓扑与网络、镜像构建、环境变量清单、首次初始化、升级与回滚、健康检查、日志与保留、备份、JVM 参数、CI/CD 流程、排障。只描述容器运行环境。

不回答：本地怎么把项目跑起来（见 `DEVELOPMENT.md`）；编码与分层规范（见 `AGENTS.md`）；各模块内部实现与选型理由（见 `specs/`）；健康探测与日志索引的内部实现（见 `specs/05-observability.md`）。

## 2. 拓扑

```
Browser ──► 宿主机 24399（LITEWORKBENCH_PORT）→ 容器 8080（Spring Boot 内置静态资源 + REST/SSE）
                │
                ├─► postgres:5432      业务数据（含用户与权限）
                ├─► redis:6379         缓存 + 登录态 + 幂等/限流 + 运行锁
                ├─► manticore:9308     消息 / 知识块 / 日志 / 文档检索（HTTP JSON API）
                ├─► minio:9000         文件对象 + 知识库文档原件
                └─► rabbitmq:5672      异步投递（日志索引、知识库解析）
```

生产环境前端静态资源由应用内置（Spring Boot `static/`），不单独部署 Nginx，减少一跳。单实例，不做集群与滚动升级。

## 3. 中间件

目标主机 2GB 内存，运行时为 Podman + podman-compose；本地开发可用 Docker 命令，语义一致。

凭据一律由 `deploy/gen-secrets.sh` 生成的 `.env.infra` 提供，**仓库内不保留任何可用口令**；口令缺失时编排以 `${VAR:?}` 直接拒绝启动，不退回任何默认值。

```bash
cd deploy && ./gen-secrets.sh              # 逐行读 .env.infra.example 生成 .env.infra（600 权限，已入 .gitignore）
set -a && . ./.env.infra && set +a         # 注入环境变量（podman-compose 1.x 不保证支持 --env-file）
podman-compose -f docker-compose.yaml up -d
```

`.env.infra.example` 是**字段清单的唯一来源**：`gen-secrets.sh` 逐行照抄该文件、只把等号右边换成随机值（`MQ_USERNAME` 与 `ADMIN_PASSWORD` 沿用示例中的值），新增字段只改 example。`gen-secrets.sh` 对已存在的 `.env.infra` 拒绝覆盖——重建会与运行中的中间件口令不一致，需要先移走旧文件。

| 服务 | 镜像 | 端口 | 凭据 |
|---|---|---|---|
| postgres | `pgvector/pgvector:pg16` | 5432 | postgres / `LITEWORKBENCH_DATASOURCE_PASSWORD` |
| redis | `redis:8` | 6379 | `LITEWORKBENCH_REDIS_PASSWORD` |
| manticore | `manticoresearch/manticore:25.0.0` | 9306（MySQL 协议）/ 9308（HTTP API） | 无 |
| minio | `pgsty/minio:RELEASE.2026-04-17` | 9000 / 9001 | `LITEWORKBENCH_STORAGE_ACCESS_KEY` / `_SECRET_KEY` |
| rabbitmq | `rabbitmq:4-management` | 5672 / 15672 | `LITEWORKBENCH_MQ_USERNAME` / `LITEWORKBENCH_MQ_PASSWORD` |

编排中的镜像取自华为云 SWR 中转地址，国内网络友好。

Redis 口令经容器内 `sh -c` 展开而非直接写进 `command`，避免明文出现在 `ps` 输出里；健康检查同样读环境变量，换口令后无需改编排。

**改口令的边界**：Postgres 的 `POSTGRES_PASSWORD`、MinIO 的 `MINIO_ROOT_PASSWORD` **只在数据卷首次初始化时生效**。已有 `pgdata` / `minio_data` 卷上直接改 `.env.infra` 再重启，只会让"编排里写着的口令"与"库/对象存储里实际的口令"不一致，应用立刻连不上。存量环境必须两步走：

```bash
# 1. 先在服务内部改真实口令（此时编排里还是旧口令）
podman exec -it pgvector psql -U postgres -c "ALTER USER postgres PASSWORD '<新口令>';"
# 2. 同步 .env.infra，再按新口令重建应用容器
bash deploy-app.sh <镜像:tag>
```

Redis 与 RabbitMQ 的凭据由环境变量在每次启动时重放，改完 `.env.infra` 重启容器即可；RabbitMQ 的账号定义落在镜像声明的匿名卷里，卷存在时以卷内定义为准。

编排同时挂在 `postgres`、`redis`、`manticore`、`minio`、`rabbitmq` 五个服务上，容器崩溃自动重启。应用容器必须加入中间件所在网络才能按主机名互访：中间件栈在宿主机上由本仓库的 `deploy/docker-compose.yaml` 启动（同步到 `/opt/liteworkbench`），项目名取目录名，故网络为 `liteworkbench_default`、命名卷为 `liteworkbench_pgdata` / `liteworkbench_minio_data`。应用编排以 `${LITEWORKBENCH_NETWORK:-liteworkbench_default}` 引用同一网络，换目录部署时用该变量对齐。

Manticore 的 9308（HTTP JSON API）只对同网络容器暴露，不发布宿主机端口；应用以 `manticore:9308` 直连，运维排障经 9306 MySQL 协议（容器内 `mysql -h0 -P9306`）执行 SQL。

RabbitMQ 拓扑由应用声明：`lw.log.exchange` / `lw.log.index.queue`（死信到 `lw.log.dlx` → `lw.log.dead.queue`）与 `lw.kb.exchange` / `lw.kb.parse.queue`。启动期 `RabbitTopologyGuard` 先于监听容器对账，历史队列参数与代码不一致时按代码声明重建并补齐绑定；对账只动应用自身命名空间的队列，不应人工去 broker 上删队列。

### 3.1 共享中间件的所有权与禁改清单

中间件是**跨项目共享基础设施**，一旦重建，同主机上其它项目的连接会一起断。数据落点分两类：`pgdata` / `minio_data` 是编排声明的命名卷；Manticore 的 `/var/lib/manticore`、RabbitMQ 的 `/var/lib/rabbitmq`、Redis 的 `/data` 由镜像自身声明为卷，落到项目命名空间下的**匿名卷**——容器重建不会删掉它们，但 **`podman volume prune` 会一次性抹掉全部会话消息、知识块与日志索引**，故禁止执行。任何部署与运维操作必须遵守：

| 规则 | 说明 |
|---|---|
| 只碰自己的 | 允许操作的容器只有 `liteworkbench`，目录只有 `/opt/liteworkbench`；中间件容器一律不 stop / rm / restart |
| 网络禁止 rm | 严禁对共享网络执行 `network rm`（Podman 会连带销毁挂载的容器）；需要改挂载只允许对单个容器 `network disconnect` + `network connect` |
| DNS 记录修复 | 主机名解析异常时只对涉事容器逐个 disconnect + connect 重建 aardvark-dns 记录，不动网络本身 |
| 新项目接入 | 要么作为 guest 加入共享网络（只加自己容器），要么用自己的编排开独立栈（独立网络 + 独立卷，端口错开）；绝不复用/修改共享栈的容器定义 |

## 4. 应用镜像

镜像由 `.github/workflows/ci.yml` 构建，编排与脚本以仓库为唯一事实源。本地手工构建：

```bash
podman build -f deploy/Dockerfile -t liteworkbench:latest .
```

`deploy/docker-compose.app.yaml` 的镜像地址为 `${LITEWORKBENCH_IMAGE:?...}`，**无默认值**，由 `deploy-app.sh <镜像:tag>` 注入。等价的手工 run 形态（口令取自 `.env.infra`）：

```bash
podman run -d --name liteworkbench -p ${LITEWORKBENCH_PORT:-24399}:8080 \
  --network ${LITEWORKBENCH_NETWORK:-liteworkbench_default} \
  --memory 1152m \
  --env-file deploy/.env.infra \
  -e LITEWORKBENCH_DATASOURCE_URL=jdbc:postgresql://postgres:5432/postgres \
  -e LITEWORKBENCH_REDIS_HOST=redis \
  -e LITEWORKBENCH_MANTICORE_URL=http://manticore:9308 \
  -e LITEWORKBENCH_MQ_HOST=rabbitmq \
  liteworkbench:latest
```

`--network` 指向中间件编排所在网络，使 `postgres`、`redis` 等主机名可解析。`--memory 1152m` 与编排内 `mem_limit` 一致：2GB 小主机上给中间件留足余量，JVM `MaxRAMPercentage=70` 据此收敛堆上限。

`deploy/Dockerfile` 三阶段：Maven 编译后端（跳过测试）→ Node 构建前端并拷入 `static/` → 运行时基于 `openjdk:17-jdk-alpine`，容器内直接安装 `python3` / `curl` / `tzdata`，并以 `--spring.web.resources.static-locations` 指向拷入的前端产物。

## 5. 配置项

全部可用环境变量覆盖，命名规则 `LITEWORKBENCH_<SECTION>_<KEY>`（Spring 与 Sa-Token 原生键保留自身前缀）。

| 环境变量 | 本地默认值 | 容器内取值 | 说明 |
|---|---|---|---|
| `LITEWORKBENCH_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/postgres` | `jdbc:postgresql://postgres:5432/postgres` | 应用编排注入 |
| `LITEWORKBENCH_DATASOURCE_USERNAME` | postgres | postgres | |
| `LITEWORKBENCH_DATASOURCE_PASSWORD` | `admin_public` | 来自 `.env.infra` | 容器部署必填，缺失编排拒绝启动 |
| `LITEWORKBENCH_REDIS_HOST` | localhost | redis | |
| `LITEWORKBENCH_REDIS_PORT` | 6379 | 6379 | |
| `LITEWORKBENCH_REDIS_PASSWORD` | `admin_public` | 来自 `.env.infra` | 容器部署必填 |
| `LITEWORKBENCH_REDIS_DATABASE` | 0 | 0 | |
| `LITEWORKBENCH_MANTICORE_URL` | `http://localhost:9308` | `http://manticore:9308` | |
| `LITEWORKBENCH_STORAGE_ENDPOINT` | `http://localhost:9000` | `http://minio:9000` | |
| `LITEWORKBENCH_STORAGE_ACCESS_KEY` | `minioadmin` | 来自 `.env.infra` | 容器部署必填 |
| `LITEWORKBENCH_STORAGE_SECRET_KEY` | `minioadminpublic` | 来自 `.env.infra` | 容器部署必填 |
| `LITEWORKBENCH_MQ_HOST` | localhost | rabbitmq | |
| `LITEWORKBENCH_MQ_PORT` | 5672 | 5672 | |
| `LITEWORKBENCH_MQ_USERNAME` | admin | 来自 `.env.infra` | |
| `LITEWORKBENCH_MQ_PASSWORD` | `admin_public` | 来自 `.env.infra` | 容器部署必填 |
| `LITEWORKBENCH_AUTH_TOKEN_TIMEOUT` | 7200 | 7200 | 登录态有效期（秒），令牌放 `Authorization` 头 |
| `LITEWORKBENCH_ADMIN_PASSWORD` | 空 | 空（`.env.infra` 留空） | 空则应用随机生成并仅在启动日志 WARN 打印一次 |
| `LITEWORKBENCH_SECURITY_SECRET` | `liteworkbench-default-secret` | 来自 `.env.infra` | 供应方密钥加密因子，生产必须覆盖 |
| `LITEWORKBENCH_LOG_DIR` | logs | `/var/log/liteworkbench` | 容器内挂 `app_logs` 卷 |
| `LITEWORKBENCH_RUNTIME_DATA_DIR` | `./data` | `/var/lib/liteworkbench` | 容器内挂 `app_data` 卷 |
| `LITEWORKBENCH_RUNTIME_PYTHON_PATH` | 空 | 空 | 空则按 数据目录 → PATH 顺序探测解释器 |
| `LITEWORKBENCH_DEGRADATION_ALLOW_START` | true | true | 中间件不可用时是否允许降级启动 |
| `LITEWORKBENCH_PORT` | 24399 | 注入编排 | **宿主端口**，由 `deploy-app.sh` 单点定义；容器内固定 8080 |
| `LITEWORKBENCH_NETWORK` | `liteworkbench_default` | `liteworkbench_default` | 应用加入的中间件网络名；中间件栈换目录部署时需对齐 |
| `LITEWORKBENCH_IMAGE` | 无 | 必填 | 应用镜像地址，由 `deploy-app.sh <镜像:tag>` 注入 |

本地默认值面向本地容器中间件；容器部署时主机名与两个目录由编排改写，密钥类一律走 `.env.infra`，不落到配置文件、不进镜像层。

## 6. 初始化

首次启动自动执行 `resources/db/schema.sql`（`spring.sql.init.mode=always` 配合幂等 `CREATE TABLE IF NOT EXISTS`），并写入种子数据：

| 类型 | 内容 |
|---|---|
| 种子账号 | `admin`（超级管理员）、`demo`（普通用户）；密码取 `LITEWORKBENCH_ADMIN_PASSWORD`（未配置时随机生成并仅在启动日志 WARN 打印一次）；两个账号置 `pwd_reset_required = true`，首登强制改密 |
| 密码接管 | 配置了 `LITEWORKBENCH_ADMIN_PASSWORD` 且 `admin` 仍处于未激活状态（`pwd_reset_required = true`）时，每次启动都把该账号口令对齐到配置值；账号一旦完成改密，配置项不再覆盖用户自己的密码 |
| 存量库升级 | `pwd_reset_required` 列以 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 补齐，默认 `false`：已存在的账号不会被强制改密 |
| 中间件与应用共用凭据 | 两者必须读同一份 `deploy/.env.infra`：中间件按它设口令，应用按它连库。两侧不一致时表现为连接认证失败，而不是静默降级 |
| 主机本地机密 | `gen-secrets.sh` 生成 `.env.infra`（600 权限，不入仓库）；`deploy-app.sh` 另会 source 同目录 `.env.app`（存在才加载）存放只属于该主机的值 |
| 内置角色 | `super_admin`、`admin`、`user`（代码常量定义，不可删除改码） |
| 菜单树 | 仪表盘、工作台、运行记录、创作台、提示词、文件、技能、知识库、日志、监控、设置、组件库、系统管理（含按钮权限点） |
| 内置工具 | Python 执行、文件读写、HTTP 请求、知识库检索（固定 ID 幂等重放） |
| 内置供应方 | `ollama` 本地供应方（无密钥，指向宿主机 Ollama） |
| 内置提示词 | 文章大纲、标题构思、润色改写、要点摘要、中英互译、代码讲解（固定 ID 幂等重放，只读） |

Manticore 的 `lw_log`、`lw_msg`、`lw_kbchunk`、`lw_doc` 表在应用启动时幂等创建。升级版本新增的菜单会自动补齐到 `super_admin` / `admin` / `user` 角色，无需手工重新授权。

## 7. 升级与回滚

`deploy/deploy-app.sh` 是唯一的应用发布入口，在 `deploy/docker-compose.app.yaml` 所在目录执行：

```bash
bash deploy-app.sh <镜像:tag>
```

| 阶段 | 行为 |
|---|---|
| 前置 | 缺失 `.env.infra` 即中止；`set -a` source `.env.infra`，存在 `.env.app` 则一并加载 |
| 端口 | `export LITEWORKBENCH_PORT=${LITEWORKBENCH_PORT:-24399}`，编排文件的宿主端口向它取，单点定义 |
| 拉取 | `podman-compose pull`，失败即退出 |
| 记录回滚锚点 | `podman inspect liteworkbench --format '{{.Image}}'` 取当前在用镜像为 `PREV_IMAGE` |
| 重建 | `recreate()`：先 `podman rm -f` 同名容器（podman-compose 的 `up -d` 只比对配置哈希、不认镜像 ID），再清同端口 `CNI-HOSTPORT-DNAT` 残留规则，最后 `podman-compose up -d` |
| 就绪自检 | `wait_ready()` 轮询 `http://127.0.0.1:<PORT>/actuator/health`，最长 3 分钟（36 × 5s），覆盖 JVM 启动 + schema 初始化 |
| 成功 | 清理悬空镜像（`podman image prune -f`），退出码 0 |
| 失败 | 打印 `podman ps` / 最近 80 行日志 / 端口监听现场；有 `PREV_IMAGE` 则重建回上一版本并再次轮询就绪，无锚点则保持现状；**无论回滚是否成功都非零码退出** |

| 动作 | 步骤 |
|---|---|
| 升级 | CI 推新镜像 → 主机执行 `bash deploy/deploy-app.sh <镜像:tag>`，schema 由幂等 DDL 自动补齐 |
| 回滚 | 脚本自带：新版本 3 分钟内未就绪即自动退回 `PREV_IMAGE` 并恢复站点，同时以非零码失败退出；人工回滚用旧镜像 tag 再跑一次 `deploy-app.sh`。新增列不删除，保证旧版本可读 |
| 中间件升级 | 不随应用发布，单独改编排并重启对应服务 |
| 用户数据导出 | `pg_dump` 按 `user_id` 过滤导出 |

## 8. 健康检查

| 端点 | 用途 |
|---|---|
| `/actuator/health` | 存活探针，也是容器 `HEALTHCHECK` 与 `deploy-app.sh` 轮询的目标 |
| `/actuator/health/readiness` | 就绪探针（`probes.enabled=true` 开启 liveness / readiness 分组） |
| `/api/v1/monitor/health` | 页面展示的逐组件状态，权限 `monitor:health:list` |

探针免登录，且 `show-details: never`，不对外暴露组件细节。容器 `HEALTHCHECK --interval=30s --timeout=5s --retries=3` 用 `curl -f http://localhost:8080/actuator/health`。

探测六个组件：

| 组件 | 探测方式 | 超时 |
|---|---|---|
| PostgreSQL | 连接校验 | 3s |
| Redis | 取一次连接后立即释放 | 2s |
| Manticore | 查真实索引（`SELECT log_id FROM lw_log LIMIT 1`） | 3s |
| MinIO | bucket exists | 5s |
| RabbitMQ | 连接打开 | 3s |
| Python | 版本探测 | 5s |

聚合状态：`UP` 全通；非核心组件失败为 `DEGRADED`；PostgreSQL 或 Redis 失败为 `DOWN`。启动自检（`StartupGuardService`）在核心组件不可用且 `LITEWORKBENCH_DEGRADATION_ALLOW_START=false` 时终止启动（fail fast），否则记 warn 降级放行。探测实现与降级细节见 `specs/05-observability.md`。

## 9. 日志与保留

| 文件 | 滚动 | 保留 |
|---|---|---|
| `liteworkbench.log` | 按天 + 100MB 分片，INFO 以上 | 30 天，总量上限 5GB |
| `liteworkbench-error.log` | 按天 + 100MB 分片，仅 WARN/ERROR | 30 天，总量上限 2GB |
| `liteworkbench-agent.log` | 按天 + 100MB 分片，Agent 链路专用 | 30 天，总量上限 2GB |
| Manticore `lw_log` | 按时间 | 30 天，每日定时任务清理 |
| Manticore `lw_msg` / `lw_kbchunk` | — | 永久，用户资产不过期 |

日志目录由 `LITEWORKBENCH_LOG_DIR` 决定，容器内为 `/var/log/liteworkbench` 并挂 `app_logs` 卷；滚动文件落在该目录的 `history/` 子目录。落盘走异步 Appender（队列满即丢弃，不回压业务线程），`com.liteworkbench.agent` / `runtime` / `service.RunService` 额外进 agent 文件。

Manticore 无文档级 TTL，日志表保留由应用定时任务（每日一次删除超过 `liteworkbench.manticore.log-retention-days` 的记录）执行；消息与知识块表无清理。

## 10. 备份

| 对象 | 方式 |
|---|---|
| PostgreSQL | `pg_dump` 每日一次，保留 7 份 |
| MinIO | `mc mirror` 到备份桶（对象键已含用户前缀，可按用户恢复） |
| Redis | **无需备份**：只承载登录态、权限/菜单/供应方缓存与幂等限流计数，全部可由 PG 重建；会话消息与知识块不在这里 |
| Manticore | **消息、知识块、日志的真正落点**：随 Manticore 数据卷持久化，卷是唯一需要跟备份的对象；知识块可从 MinIO 原件重解析，日志 30 天过期即弃 |
| 配置 | 环境变量清单入库外保管 |

## 11. JVM 参数

云主机规格小，容器固定以下一组参数（`deploy/Dockerfile` 的 `ENV JAVA_OPTS`）：

```
-XX:+UseG1GC -XX:MaxGCPauseMillis=200
-XX:MaxRAMPercentage=70
-XX:+UseStringDeduplication
-XX:+UseCompressedOops -XX:+UseCompressedClassPointers
-Xss512k
-XX:+ExitOnOutOfMemoryError
```

容器内存上限由 `docker-compose.app.yaml` 的 `mem_limit: 1152m` 设定。

| 参数 | 理由 |
|---|---|
| `UseG1GC` + `MaxGCPauseMillis=200` | 小堆下停顿可控 |
| `MaxRAMPercentage=70` | 给堆外内存与 PageCache 留位置，避免容器 OOM Kill |
| `UseStringDeduplication` | 日志与事件文本重复率高，去重省堆 |
| `UseCompressedOops` / `UseCompressedClassPointers` | 指针与对象头压缩，显式声明防止被覆盖 |
| `Xss512k` | 线程栈从默认 1m 降到 512k，同等内存支撑更多 SSE 与工具线程 |
| `ExitOnOutOfMemoryError` | OOM 直接退出交给编排重启，不留半死状态 |

## 12. CI/CD

流水线定义在 `.github/workflows/ci.yml`，三个 job：

| job | 触发 | 动作 |
|---|---|---|
| `backend` | push 与 pull_request | JDK 17 + Maven 缓存，`mvn -B -DskipTests=false package`（编译并跑测试） |
| `frontend` | push 与 pull_request | Node 22 + npm 缓存，`npm install` → `npm run typecheck` → `npm run build` |
| `release` | 仅 `main` 的 push，且 `backend` / `frontend` 通过 | 构建并推送镜像，同步编排与脚本到主机，执行发布脚本 |

`release` 步骤：

1. 用内置 `GITHUB_TOKEN` 登录 GHCR；
2. `docker build-push-action` 以 `deploy/Dockerfile` 构建，推送 `ghcr.io/<owner>/liteworkbench:latest` 与 `ghcr.io/<owner>/liteworkbench:<sha>` 两个 tag；
3. 用 scp 把 `deploy/docker-compose.app.yaml`、`deploy/deploy-app.sh` 分别同步到主机 `SSH_APP_DIR`（编排与发布脚本以仓库为唯一事实源；`strip_components: 1` 去掉 `deploy/` 前缀，两文件各占一个 step，因为该 action 的 `source` 只接受单个路径）；
4. SSH 执行 `cd $SSH_APP_DIR && bash deploy-app.sh ghcr.io/<owner>/liteworkbench:latest`。

CI **不注入任何部署密钥**：应用密钥、中间件口令只来自目标主机的 `.env.infra`，`deploy-app.sh` 在主机上自行 source。

| Secret | 用途 |
|---|---|
| `SSH_HOST` | 目标主机地址 |
| `SSH_USER` | 登录用户 |
| `SSH_KEY` | SSH 私钥 |
| `SSH_APP_DIR` | 主机上存放编排与脚本的目录 |
| `GITHUB_TOKEN` | 内置，用于登录 GHCR |

前置条件：仓库 Actions 处于启用状态，且三个 SSH Secret 配置齐全；流水线未跑或 secret 缺失时主机不会更新。GHCR 镜像跟随仓库可见性：仓库为 public 时镜像可匿名拉取，若为 private 需在主机先 `docker login ghcr.io`。

中间件不在 CI 中重建，只在主机上单独维护；应用与中间件靠共享网络互通。

## 13. 取舍

| 选择 | 换来什么 | 代价 |
|---|---|---|
| 静态资源内置到 jar | 少一个 Nginx 容器，部署简单 | 前端更新需重新打镜像 |
| 应用与中间件分成两个编排 | 发布应用不动数据库 | 需要跨 compose 共享网络，网络名随中间件栈的项目名变化，故用 `LITEWORKBENCH_NETWORK` 显式指定而非依赖默认值 |
| 单实例 | 无分布式一致性问题 | 无法水平扩展，升级有短暂中断 |
| 启动自动建表 | 首次部署零手工步骤 | 需要 DDL 保持幂等 |
| 发布脚本自带回滚 | 起不来不会把站点留在宕机状态 | 需要保留上一版镜像，回滚有额外等待 |
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
| 会话提交报 `SESSION_BUSY` | 该会话有运行未收尾 | 等锁 TTL 自动释放，或对残留运行执行 abort |
| 运行中断不生效 | abort 后仍输出 | 看 `[run.abort]` 日志：有活动实例走取消，无实例则直接结算终态 |
| 健康页 manticore DOWN，detail 含 `only SELECT queries are supported` | `/sql` 非 raw 模式把语句编译成搜索查询，无 `FROM <索引>` 的语句会被 501 | 探测语句必须查真实索引（如 `SELECT log_id FROM lw_log LIMIT 1`）；要跑任意语句得加 `raw_response=true`；`/cli` 失败也回 `Query OK`，不能当健康判据 |
| 启动日志出现 `Multiplier must be > 1.0 for effective exponential backoff` | RabbitMQ 监听重试的 `multiplier` 为默认 1.0 | 应用已显式配置 `initial-interval` / `max-interval` / `multiplier=2`；仍出现说明配置被覆盖 |
| 启动日志出现 `Standard Commons Logging discovery ... remove commons-logging.jar` | 检索 SDK 传递引入 `commons-logging` | 已在 Maven 里 exclude；仍出现说明有新依赖带进来 |
| 模型调用失败 4002 / 4003 | 运行失败 | 供应方页面测试连通；核对 baseUrl（含 `/v1`）、模型名、密钥 |
| 无可用供应方 4001 | 对话直接失败 | 供应方列表为空或 `enabled=false`：补充配置并启用 |
| Python 7001 | 工具返回运行时未就绪 | 容器内确认 `python3 --version`，或用 `LITEWORKBENCH_RUNTIME_PYTHON_PATH` 指定，监控页重探 |
| 脚本越界 7004 | 工具返回不安全路径 | 脚本工作目录限定在数据目录内，确认脚本未引用目录之外的路径 |
| 文件 6002 / 6003 | 上传失败 / 超限 | 查 MinIO 健康与桶存在性；单文件上限 100MB |
| 知识库文档一直 pending | 状态不流转 | MQ 不可用或消费者掉线：看 `[kb.parse]` 日志，恢复后重解析 |
| 知识库 8604 / 8605 | 上传被拒 / 解析失败 | 8604 换支持的格式；8605 看文档行 `error_message`，修原件后重解析 |
| 会话消息 / 知识块读不到 | 旧会话消息不全 | 这两类是 Manticore 文档，与 Redis 无关：查 Manticore 容器与数据卷是否被重建、`lw_msg` / `lw_kbchunk` 索引是否还在 |
| 日志中心查不到 | 检索为空 | MQ 降级时日志只落文件（记 warn）；文档 30 天过期即不可查；普通用户只看到自己的日志，排查他人需管理员（`monitor:log:global`） |
| 升级后日志检索报 `Unknown column user_id` | `lw_log` 仍是旧结构且启动期补列未生效 | 重启应用触发幂等建表与补列；仍失败时在容器内 `curl -X POST 'http://manticore:9308/cli' -H 'Content-Type: text/plain' --data 'ALTER TABLE lw_log ADD COLUMN user_id string'` 手工补齐后重探 |
| 事件回放缺尾部 | 回放不完整 | 事件按 200ms 定时攒批落库，进程被强杀可能丢最后一批；以会话助手消息为准 |
| 容器连不上中间件 | Connection refused 但中间件健康 | aardvark-dns 残留记录把主机名解析到旧 IP：对涉事容器逐个 `network disconnect` + `connect`；严禁对共享网络 `network rm` |
| 端口不可达但容器在跑 | 容器内 8080 返回 UP，宿主机端口失败 | rootless Podman 的 `CNI-HOSTPORT-DNAT` 残留指向已删容器 IP：`deploy-app.sh` 重建前会清理该端口的残留规则，或按脚本方式手工删规则后重建 |
| 磁盘紧张 | 容器占用上涨 | 日志有 30 天滚动与总量上限；Redis 256MB LRU；MinIO 按需清理 |

### 14.3 运维约束

- 排障不得打印密钥与令牌；需要核对密钥时只在供应方页面看脱敏值。
- 生产不开 debug 级日志，排查依赖 info 字段与 traceId 检索。
- 手工改库前先 `pg_dump` 对应表。
