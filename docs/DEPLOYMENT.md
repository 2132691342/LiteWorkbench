# DEPLOYMENT

容器部署、配置项、运维动作。只支持容器运行环境。

---

## 1. 定位

单实例容器部署。中间件由 `deploy/docker-compose.yaml` 提供，应用由 `deploy/Dockerfile` 构建。不做集群、不做滚动升级。开发服务器 `47.108.73.153` 部署同一套编排。

## 2. 拓扑

```
Browser ──► liteworkbench:8080 (Spring Boot 内置静态资源 + REST/SSE)
                │
                ├─► postgres:5432      业务数据（含用户与权限）
                ├─► redisearch:6379    缓存 + 登录态 + 日志索引
                ├─► minio:9000         文件对象
                └─► rabbitmq:5672      异步日志投递
```

生产环境前端静态资源由应用内置（Spring Boot `static/`），不单独部署 Nginx，减少一跳。

## 3. 中间件

```bash
docker compose -f deploy/docker-compose.yaml up -d
```

| 服务 | 镜像（华为云 SWR） | 端口 | 凭据 |
|---|---|---|---|
| postgres | `pgvector/pgvector:pg16` | 5432 | postgres / admin_public |
| redisearch | `redislabs/redisearch:2.4.14` | 6379 | 密码 admin_public |
| minio | `pgsty/minio:RELEASE.2026-04-17` | 9000 / 9001 | minioadmin / minioadminpublic |
| rabbitmq | `rabbitmq:4-management` | 5672 / 15672 | admin / admin_public |

编排同时挂在 `postgres`、`redis`、`minio`、`rabbitmq` 四个服务上，容器崩溃自动重启。

## 4. 应用镜像

```bash
docker build -f deploy/Dockerfile -t liteworkbench:latest .
docker run -d --name liteworkbench -p 8080:8080 \
  --network liteworkbench_default \
  -e LITEWORKBENCH_DATASOURCE_URL=jdbc:postgresql://postgres:5432/postgres \
  -e LITEWORKBENCH_DATASOURCE_PASSWORD=admin_public \
  -e LITEWORKBENCH_REDIS_PASSWORD=admin_public \
  -e LITEWORKBENCH_STORAGE_ACCESS_KEY=minioadmin \
  -e LITEWORKBENCH_STORAGE_SECRET_KEY=minioadminpublic \
  -e LITEWORKBENCH_MQ_USERNAME=admin \
  -e LITEWORKBENCH_MQ_PASSWORD=admin_public \
  liteworkbench:latest
```

`--network` 指向 compose 创建的网络（`docker network ls` 查实际名称），使 `postgres`、`redisearch` 等主机名可解析。构建为三阶段：Maven 编译后端 → Node 编译前端并拷入 `static/` → 运行时镜像只放 JRE 与 jar。

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
| 菜单树 | 仪表盘、工作台、文件、技能、日志、监控、设置、系统管理（含按钮权限点） |
| 内置工具 | Python 执行、文件读写、HTTP 请求 |

Redisearch 的 `idx:lw:log` 索引在应用启动时幂等创建。

## 7. 升级与回滚

| 动作 | 步骤 |
|---|---|
| 升级 | `docker compose pull` → 重新 `docker run`，schema 由幂等 DDL 自动补齐 |
| 回滚 | 换回旧镜像 tag；涉及新增列时不删列，保证旧版本可读 |
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

Redisearch 索引文档带 TTL 语义，清理由定时任务按 `ts` 范围删除。

## 10. 备份

| 对象 | 方式 |
|---|---|
| PostgreSQL | `pg_dump` 每日一次，保留 7 份 |
| MinIO | `mc mirror` 到备份桶（对象键已含用户前缀，可按用户恢复） |
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

流水线定义在 `.github/workflows/ci.yml`，三个阶段串行：`server`（编译 + 测试）→ `web`（类型检查 + 构建）→ `release`（仅 `main` 的 push 触发）。

发布动作：构建镜像推送到 GHCR（`:latest` 与 `:<sha>` 两个 tag），再 SSH 到云主机 `47.108.73.153` 执行 `docker compose -f docker-compose.app.yaml pull && up -d`。

| Secret | 用途 |
|---|---|
| `SSH_HOST` | 云主机地址 |
| `SSH_USER` | 登录用户 |
| `SSH_KEY` | SSH 私钥 |
| `SSH_APP_DIR` | 主机上存放 `docker-compose.app.yaml` 的目录 |
| `GITHUB_TOKEN` | 内置，用于登录 GHCR |

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
