#!/usr/bin/env bash
# 应用发布：拉镜像 → 重建容器 → 清端口转发残留 → 健康自检，未就绪自动回滚上一版本。
# 用法：bash deploy-app.sh <镜像:tag>，在 docker-compose.app.yaml 所在目录执行。
set -uo pipefail

IMAGE="${1:?用法: deploy-app.sh <镜像:tag>}"
CONTAINER="${CONTAINER:-liteworkbench}"
COMPOSE="${COMPOSE:-docker-compose.app.yaml}"
# 宿主端口在此定义一次，编排文件向本脚本取；容器内固定 8080
export LITEWORKBENCH_PORT="${LITEWORKBENCH_PORT:-24399}"
PORT="$LITEWORKBENCH_PORT"
HEALTH_URL="http://127.0.0.1:${PORT}/actuator/health"
# podman-compose 会把完整 podman run 命令（含全部 -e 明文口令）打到 stdout，CI 日志只脱敏 Secret 不脱敏这些值，
# 因此编排输出只落服务器本地文件，失败时才回显尾部
COMPOSE_LOG="${COMPOSE_LOG:-/var/log/liteworkbench-compose.log}"
# 就绪等待：最长 3 分钟（36 × 5s），需覆盖 JVM 启动 + schema 初始化
READY_TRIES=36
READY_INTERVAL=5

log() { echo "[deploy] $*"; }

# 部署机密：由 gen-secrets.sh 生成，应用与中间件必须共用同一份，缺失即中止
ENV_FILE="${ENV_FILE:-.env.infra}"
if [ ! -f "$ENV_FILE" ]; then
  echo "[deploy] 缺少 $ENV_FILE，先执行 ./gen-secrets.sh 生成" >&2
  exit 1
fi

# 用 source 注入而非 --env-file：podman-compose 1.x 不保证支持该参数
set -a
# shellcheck disable=SC1090
. "./$ENV_FILE"
# 主机本地机密（初始口令等）放同目录 .env.app，不入仓库
if [ -f .env.app ]; then
  log "加载 .env.app"
  # shellcheck disable=SC1091
  . ./.env.app
fi
set +a

# rootless podman 重建后会留下指向已删容器的 CNI-HOSTPORT-DNAT 规则，导致宿主机端口不可达
drop_stale_dnat() {
  command -v iptables >/dev/null 2>&1 || { log "无 iptables，跳过 DNAT 清理"; return 0; }
  local dropped=0 ln
  while true; do
    ln=$(iptables -t nat -L CNI-HOSTPORT-DNAT -n --line-numbers 2>/dev/null \
      | grep "$PORT" | head -1 | awk '{print $1}' || true)
    [ -z "$ln" ] && break
    iptables -t nat -D CNI-HOSTPORT-DNAT "$ln" 2>/dev/null || break
    dropped=$((dropped + 1))
    [ "$dropped" -gt 30 ] && break
  done
  log "清理 CNI-HOSTPORT-DNAT 残留规则 $dropped 条"
}

# 轮询健康端点，就绪返回 0
wait_ready() {
  local i
  for i in $(seq 1 "$READY_TRIES"); do
    if curl -fsS "$HEALTH_URL" >/dev/null 2>&1; then
      return 0
    fi
    sleep "$READY_INTERVAL"
  done
  return 1
}

# 前置检查：确认中间件网络存在且 postgres 在网络上可达。
# 若中间件不在线，新容器必然因 UnknownHostException 崩溃循环，回滚同样起不来；
# 此时应中止发布、保留现场（不动当前容器），并提示先拉起 docker-compose.yaml。
preflight_infra() {
  local net="${LITEWORKBENCH_NETWORK:-liteworkbench_default}"
  if ! podman network exists "$net"; then
    echo "[deploy] 预检失败：中间件网络 $net 不存在。请在部署目录执行：" >&2
    echo "  podman-compose -f docker-compose.yaml up -d" >&2
    return 1
  fi
  # 用 postgres 容器自身做 DNS+连通探针：既验证网络归属，也验证 pg 服务真的在应答
  if ! podman exec pgvector pg_isready -h postgres -U postgres >/dev/null 2>&1; then
    echo "[deploy] 预检失败：$net 上的 postgres 主机名或数据库不可达。请检查 pgvector 容器状态与 .env.infra 口令" >&2
    return 1
  fi
  log "中间件预检通过：network=$net"
  return 0
}

# podman-compose 的 up -d 只比对配置哈希不认镜像 ID，重建前必须删掉同名容器
recreate() {
  podman rm -f "$CONTAINER" 2>/dev/null || true
  drop_stale_dnat
  : > "$COMPOSE_LOG" 2>/dev/null || true
  chmod 600 "$COMPOSE_LOG" 2>/dev/null || true
  if ! podman-compose -f "$COMPOSE" up -d >>"$COMPOSE_LOG" 2>&1; then
    echo "编排启动失败，尾部日志（已滤掉环境变量明文）：" >&2
    tail -30 "$COMPOSE_LOG" | grep -av -- ' -e LITEWORKBENCH' >&2 || true
    return 1
  fi
}

log "拉取镜像 $IMAGE"
export LITEWORKBENCH_IMAGE="$IMAGE"
podman-compose -f "$COMPOSE" pull || { echo "拉取镜像失败"; exit 1; }

# 预检：中间件必须在线，否则新版本与回滚版本都起不来；不动当前容器
preflight_infra || { echo "发布中止：中间件未就绪，当前容器未改动"; exit 1; }

# 回滚锚点：记录当前在用镜像，新版本起不来就退回它，避免把站点留在宕机状态
PREV_IMAGE=$(podman inspect "$CONTAINER" --format '{{.Image}}' 2>/dev/null || true)
log "当前在用镜像 ${PREV_IMAGE:-none}"

log "启动容器"
recreate || { echo "启动容器失败"; exit 1; }

sleep 5
log "端口映射"
podman port "$CONTAINER" || true

log "等待就绪"
if wait_ready; then
  log "就绪，清理悬空镜像"
  podman image prune -f || true
  log "liteworkbench ready on :$PORT"
  exit 0
fi

echo "=== 新版本未就绪，打印现场 ==="
podman ps --format '{{.Names}} | {{.Status}} | {{.Ports}}'
# 滤掉 data.init.user 行：种子账号初始口令只允许出现在服务器日志，不得随 CI 日志外泄
APP_LOGS=$(podman logs --tail 80 "$CONTAINER" 2>&1 | grep -av 'data.init.user' || true)
# 常见故障模式识别：UnknownHostException 说明中间件主机名不可达，给出可操作的修复建议
if echo "$APP_LOGS" | grep -q 'UnknownHostException'; then
  echo ">>> 检测到主机名解析失败——先确认 docker-compose.yaml 中间件容器已启动（podman ps 里有没有 pgvector/redis 等）<<<" >&2
fi
echo "$APP_LOGS"
ss -lntp 2>/dev/null | grep "$PORT" || echo "$PORT 未监听"

if [ -z "$PREV_IMAGE" ]; then
  echo "无可回滚镜像，保持现状并失败退出"
  exit 1
fi

log "回滚到上一版本镜像 $PREV_IMAGE"
export LITEWORKBENCH_IMAGE="$PREV_IMAGE"
recreate || { echo "回滚启动失败"; exit 1; }
sleep 5
if wait_ready; then
  echo "=== 已回滚到 $PREV_IMAGE，站点恢复；本次发布判定失败 ==="
else
  echo "=== 回滚后仍未就绪，需要人工介入 ==="
  APP_LOGS2=$(podman logs --tail 80 "$CONTAINER" 2>&1 | grep -av 'data.init.user' || true)
  if echo "$APP_LOGS2" | grep -q 'UnknownHostException'; then
    echo ">>> 回滚同样失败且报主机名解析错误——问题在中间件或网络，与新旧镜像无关，需人工拉起 docker-compose.yaml <<<" >&2
  fi
  echo "$APP_LOGS2"
fi
exit 1
