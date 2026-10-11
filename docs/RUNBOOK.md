# RUNBOOK

排障手册：出问题时从哪看、按什么顺序查、每类故障怎么处置。只写可执行动作。

---

## 1. 分诊路径

```
现象
  ├─ 页面打不开 / 端口不可达
  │    → 0 端口转发：宿主机 `curl 127.0.0.1:24399/actuator/health`；容器在跑却失败见场景处置
  ├─ 页面打不开 / 接口 500
  │    → 1 容器存活：podman ps + HEALTHCHECK 状态
  │    → 2 应用日志：liteworkbench-error.log 最近 50 行
  │    → 3 健康页：/api/v1/monitor/health（能登录时）
  ├─ 单个功能异常
  │    → 1 错误码定位领域（见 ERROR-CODES.md 分段）
  │    → 2 日志中心按 traceId 检索（响应信封 requestId 即 traceId）
  └─ 运行行为异常（卡住 / 中断不了 / 输出不全）
       → 1 运行详情 + 事件回放（工作台右侧运行历史）
       → 2 agent 日志：liteworkbench-agent.log
```

## 2. 场景处置

| 场景 | 判据 | 处置 |
|---|---|---|
| 容器反复重启 | HEALTHCHECK 失败、日志出现 OOM | `-XX:+ExitOnOutOfMemoryError` 生效即重启；查 `podman stats` 是否超限，必要时调大容器内存 |
| 启动失败：缺配置 | 日志含环境变量名 | 补 `LITEWORKBENCH_SECURITY_SECRET` 等必填项后重启；生产禁用开发默认密钥 |
| 启动失败：DDL 报错 | `spring.sql.init` 日志 | schema.sql 幂等，检查是否手工改过表结构导致类型冲突 |
| 全部接口 401 | 登录后仍 401 | Redis 被清空或重启：Sa-Token 会话丢失属预期，重新登录；检查 Redis 是否 `noeviction` 被写满 |
| 会话提交报 3002 | `SESSION_BUSY` | 该会话有运行未收尾：等 15 分钟锁 TTL 自动释放，或对残留运行执行 abort |
| 运行中断不生效 | abort 后仍输出 | 看 `[run.abort]` 日志：有活动实例走 dispose，无实例（孤儿运行）直接结算终态 |
| 模型调用 4002 / 4003 | 运行失败、`MODEL_CALL_*` | 供应方页面测试连通；核对 baseUrl（含 `/v1`）、模型名、密钥；超时上限 180 秒 |
| 无可用供应方 4001 | 对话直接失败 | 供应方列表为空或 enabled=false：补充配置并启用 |
| Python 7001 | 工具返回运行时未就绪 | 容器内 `python3 --version`；无则装 python3 或用 `LITEWORKBENCH_RUNTIME_PYTHON_PATH` 指路径，监控页重探 |
| 脚本越界 7004 | 工具返回不安全路径 | 脚本工作目录限定 `{data-dir}` 内；确认脚本未引用目录之外的路径 |
| 文件 6002 / 6003 | 上传失败 / 超限 | MinIO 健康与桶存在性；单文件上限 100MB |
| 日志中心查不到 | 检索为空 | MQ 降级时日志只落文件（记 warn）；索引由启动幂等创建；文档 30 天 TTL 过期即不可查 |
| 仪表盘无数据 | 图表为空 | `monitor/overview` 直连查看；无运行记录时趋势为空属正常 |
| 磁盘紧张 | 容器占用上涨 | 日志目录含 30 天滚动（全量 5GB + 另外两份 2GB 上限）；Redis 256MB LRU；MinIO 按需清理 |
| 事件回放缺尾部 | 回放不完整 | 事件按 100 条 / 200ms 攒批，断电可能丢最后一批；以 wb_message 的助手消息为准 |
| 容器连不上中间件 | Connection refused 但中间件健康 | aardvark-dns 残留记录把主机名解析到旧 IP：对涉事容器逐个 `podman network disconnect` + `connect` 重建记录；**严禁对共享网络 `network rm`**（会连带销毁挂载的容器） |
| 共享中间件容器丢失 | minio/pgvector 等不存在 | 用 `/root/workbaby-infra/docker-compose.yaml` 执行 `podman-compose up -d` 按原卷重建（卷在数据在）；原件参照 `/opt/workbaby-infra` |
| 端口不可达（外部 502 或连接被拒）但容器在跑 | 容器内 `podman exec liteworkbench wget -qO- 127.0.0.1:8080/actuator/health` 返回 UP，宿主机 `curl 127.0.0.1:24399` 失败 | podman 3.x rootless 反复重建同名容器会在 `CNI-HOSTPORT-DNAT` 留下指向已删容器 IP 的历史规则，新容器流量被转发到不存在的地址：`iptables -t nat -L CNI-HOSTPORT-DNAT -n --line-numbers` 逐条删除含该端口的规则，再 `podman rm -f liteworkbench && podman-compose -f docker-compose.app.yaml up -d`；流水线部署前已内置同端口残留清理 |
| 端口规则指向旧容器 | `iptables -t nat -S CNI-HOSTPORT-DNAT` 中同一端口出现多条 DNAT | 处置同上；清理后应只剩一条，且 `--to-destination` 的 IP 等于 `podman inspect liteworkbench` 的容器 IP |

## 3. 备份与恢复

| 对象 | 备份 | 恢复 |
|---|---|---|
| PostgreSQL | `pg_dump` 每日，保留 7 份 | `psql` 导入；schema 由幂等 DDL 兜底 |
| MinIO | `mc mirror` 到备份桶 | 反向 mirror；对象键含用户前缀，可按用户恢复 |
| Redis | 不备份 | 登录态、幂等占位与缓存可重建；丢失只影响在跑请求与登录会话 |
| 配置 | 环境变量清单库外保管 | 按清单重建容器 |

## 4. 升级与回滚

| 动作 | 步骤 |
|---|---|
| 升级 | CI 推新镜像 → 主机 `podman-compose pull && up -d`；schema 自动补齐，种子菜单逐条补齐 |
| 回滚 | 换回旧镜像 tag；新增列不删除，旧版本可读 |
| 中间件升级 | 不随应用发布，单独改 compose 并重启对应服务 |

## 5. 约束

- 排障不得打印密钥与令牌；需要核对密钥时只在供应方页面看脱敏值。
- 生产环境禁止开启 debug 级日志，排查依赖 info 字段与 traceId 检索。
- 手工改库前先 `pg_dump` 对应表。
