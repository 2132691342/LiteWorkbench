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

# podman-compose 的 up -d 只比对配置哈希不认镜像 ID，重建前必须删掉同名容器
recreate() {
  podman rm -f "$CONTAINER" 2>/dev/null || true
  drop_stale_dnat
  podman-compose -f "$COMPOSE" up -d
}

log "拉取镜像 $IMAGE"
export LITEWORKBENCH_IMAGE="$IMAGE"
podman-compose -f "$COMPOSE" pull || { echo "拉取镜像失败"; exit 1; }

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
podman logs --tail 80 "$CONTAINER" || true
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
  podman logs --tail 80 "$CONTAINER" || true
fi
exit 1
