# DEVELOPMENT

本地把项目跑起来、改一处东西知道去哪改。

---

## 1. 定位

面向在本机开发与调试的人。只回答：需要装什么、怎么起、怎么验证、改东西去哪。**不讲架构**（见 [ARCHITECTURE.md](ARCHITECTURE.md)）、**不讲规范**（见 [AGENTS.md](../AGENTS.md)）、**不讲部署**（见 [DEPLOYMENT.md](DEPLOYMENT.md)）。

## 2. 前置条件

| 工具 | 版本 | 用途 |
|---|---|---|
| JDK | 17 | 编译与运行后端 |
| Maven | 3.9+ | 后端构建 |
| Node | 22 | 前端构建 |
| npm | 10+ | 前端依赖管理 |
| Docker 或 Podman | 24+ / 3.4+ | 起中间件 |

Maven 本地仓库默认 `~/.m2/repository`；想复用已有缓存时用 `-Dmaven.repo.local=<目录>` 覆盖，不要改全局 `settings.xml`。

## 3. 目录约定

```
backend/src/main/java/com/liteworkbench/
  api/ service/ mapper/ domain/{entity,dto,vo,constant}/
  config/ anno/ aspect/ mq/{producer,consumer}/ agent/ runtime/
  observability/ common/
backend/src/main/resources/
  mapper/*.xml  db/schema.sql  logback-spring.xml  application.yaml

frontend/src/
  api/ components/{layout,ui,charts,console,runs,settings}/ composables/ directives/
  locales/ router/ stores/ styles/ types/ utils/ views/
```

各包的职责与依赖方向见 [ARCHITECTURE.md](ARCHITECTURE.md) 的「分层与依赖方向」；命名规则见 [AGENTS.md](../AGENTS.md) §2。

新增一个子系统时按顺序改动五处，缺一处就会被契约测试或运行期发现：

1. `domain/entity` 加实体，`resources/db/schema.sql` 加建表（用户拥有的业务表必须带 `user_id`）；
2. `mapper` 加接口（单表增删改查写注解，动态 SQL 才落 XML）；
3. `service` 加编排；
4. `api` 加控制器（GET/POST、路径参数放末尾、写接口加 `@Idempotent` 与 `@SaCheckPermission`）；
5. `specs/` 补设计文档，[API-CONTRACT.md](API-CONTRACT.md) 补接口，`AGENTS.md` 如需新增权限点则同步菜单种子。

## 4. 起服务

### 4.1 中间件

```bash
cd deploy
./gen-secrets.sh                                     # 生成 .env.infra，字段清单见 .env.infra.example
set -a && . ./.env.infra && set +a
docker compose -f docker-compose.yaml up -d          # 或 podman-compose
```

本机后端要直连 Manticore 时追加 `-f docker-compose.local.yaml`，把 9306 / 9308 发布到宿主机。

### 4.2 后端

```bash
cd backend
mvn spring-boot:run        # 直接运行，默认 8080
mvn -DskipTests package    # 打包成 target/lite-workbench-server.jar
mvn test                   # 跑测试
```

启动时幂等执行建表、种子数据与 Manticore 索引创建。中间件未就绪时按 `liteworkbench.degradation.allow-start` 决定是降级启动还是失败退出。

### 4.3 前端

```bash
cd frontend
npm install
npm run dev         # http://localhost:5173，/api 已代理到 8080
npm run typecheck   # vue-tsc --noEmit
npm run build       # 产物 frontend/dist
```

前端只有一个环境变量 `VITE_API_BASE_URL`（定义在 `.env.development`，缺省 `/api/v1`）。

## 5. 本地与容器的配置差异

配置项的**全量清单与语义见 [DEPLOYMENT.md](DEPLOYMENT.md) §5**，这里只列本地与容器取值不同的几项：

| 配置 | 容器内 | 本地 |
|---|---|---|
| `liteworkbench.runtime.data-dir` | `/var/lib/liteworkbench` | `./data` |
| `LITEWORKBENCH_LOG_DIR` | `/var/log/liteworkbench` | `logs` |
| `liteworkbench.manticore.url` | `http://manticore:9308`（服务名直连） | `http://localhost:9308`（需 local 覆盖文件发布端口） |

固定阈值**不外部化**，固化在 `domain/constant/*Const`（例如 `KbConst.MAX_DOC_CHARS` 单文档提取正文上限、`OutputSanitizer.MAX_CHARS` 工具输出保尾长度、`AgentEventAdapter.TEXT_DELTA_MAX` 单帧增量截断、`MemoryConst.MAX_PER_USER` 记忆条数上限）。改动这些常量等于改契约，需同步对应 `specs/`。

## 6. 测试

当前 2 个文件 5 个用例，一次 `mvn test` 跑完（约 1 秒），全部不依赖中间件与 Spring 容器。用例清单与保留标准见 [AGENTS.md](../AGENTS.md) §14。

```bash
cd backend && mvn test
```

前端只有类型检查，没有测试运行器；`npm run typecheck` 与 `npm run build` 就是前端 CI 的全部校验。

## 7. 常见改动入口

| 想改什么 | 改哪里 |
|---|---|
| 加一个工具 | `agent/tool/WorkbenchTools.java` + `resources/db/schema.sql` 的 `wb_tool` 种子 |
| 加一个模型协议 | `agent/model/` 新增客户端 + `agent/ModelFactory.java` 登记 + `domain/constant/ProviderPresetEnum.java` |
| 改推理循环 | `agent/loop/AgentLoop.java`（纯逻辑，可离线单测） |
| 改事件归一 | `agent/AgentEventAdapter.java` + `domain/constant/EventTypeEnum.java` |
| 改推送节拍或水位快照 | `service/RunService.java` 的 `pumpLocked` / `pushSnapshotIfNeeded` + `domain/constant/RunConst.java` 的节拍常量 |
| 改规划器 / 记忆 / 子运行 | `agent/tool/PlanNormalizer.java`、`service/MemoryService.java`、`service/SubRunService.java` |
| 多出一个线程池 | `config/ExecutorConfig.java`（禁止在业务类里裸建线程池，见 AGENTS.md §17） |
| 加一个权限点 | `config/DataInitializer.java` 的菜单种子（`menu_type=button`）+ 控制器注解，`ContractTest` 会校验一致性 |
| 加一个内置角色 | `domain/constant/RoleCodeConst.java` + `DataInitializer` 的角色与授权 |
| 加一类日志检索字段 | `observability/ManticoreLogAppender.java` + `mapper/LogSearchMapper.java` 表定义 |
| 加一种知识库文档格式 | `service/DocTextExtractor.java` + `domain/constant/KbConst.java` 扩展名清单 |
| 加一个 AI 辅助动作 | `domain/constant/DocConst.java` 的 `ASSIST_TEMPLATES` + 前端动作下拉与文案 |
| 加一种在线预览格式 | `views/files/FileList.vue` 的 `kindOf` 与对应渲染分支 |
| 加一个前端页面 | `frontend/src/views/` + `DataInitializer` 菜单种子（`component` 指向该文件） |
| 改配色或控件形态 | `frontend/src/styles/tokens.css`（唯一色值来源）+ `components/ui/` |
| 加一个写接口 | `@SaCheckPermission` + `@Idempotent` + `@PostMapping` |

## 8. 仓库里的可执行资产

只有下面这些，各自单一职责，不在别处另放临时脚本：

| 文件 | 职责 |
|---|---|
| `deploy/Dockerfile` | 三阶段构建：Maven 打包 → Vite 构建 → 运行时镜像（JRE + jar + 前端静态资源 + python3 / curl / tzdata） |
| `deploy/docker-compose.yaml` | 中间件编排，本机与云主机共用同一份 |
| `deploy/docker-compose.app.yaml` | 应用容器编排：单容器、限内存、接入中间件网络、宿主端口由环境注入 |
| `deploy/docker-compose.local.yaml` | 本地开发覆盖：只把 Manticore 的 9306 / 9308 发布到宿主机 |
| `deploy/gen-secrets.sh` | 生成 `.env.infra`：字段清单取自 `.env.infra.example`，值随机，不打印 |
| `deploy/.env.infra.example` | 部署机密的字段清单，新增变量只改这里 |
| `deploy/deploy-app.sh` | 云主机侧发布：拉镜像 → 重建容器 → 清端口转发残留 → 健康自检 → 失败回滚 |
| `.github/workflows/ci.yml` | 流水线：后端构建与测试 → 前端类型检查与构建 → 构建镜像推仓库 → 同步编排与脚本 → SSH 执行发布 |
