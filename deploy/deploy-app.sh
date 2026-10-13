#!/usr/bin/env bash
# 应用发布：拉取镜像、重建容器、清理端口转发残留、健康就绪自检
# 用法：bash deploy-app.sh <镜像:tag>，工作目录为 docker-compose.app.yaml 所在目录
set -uo pipefail

IMAGE="${1:?用法: deploy-app.sh <镜像:tag>}"
CONTAINER=liteworkbench
COMPOSE=docker-compose.app.yaml
PORT=24399

log() { echo "[deploy] $*"; }

# 主机本地机密（初始密码等）放同目录 .env.app，不入仓库；缺失则沿用 CI 注入的变量
if [ -f .env.app ]; then
  log "加载主机本地环境变量 .env.app"
  set -a
  . ./.env.app
  set +a
fi

# rootless podman 重建后会留下指向已删容器的 CNI-HOSTPORT-DNAT 规则，导致宿主机端口不可达
drop_stale_dnat() {
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

log "拉取镜像 $IMAGE"
export LITEWORKBENCH_IMAGE="$IMAGE"
podman-compose -f "$COMPOSE" pull || { echo "拉取镜像失败"; exit 1; }

# 回滚锚点：记录当前在用镜像，新版本起不来就退回它，避免把站点留在宕机状态
PREV_IMAGE=$(podman inspect "$CONTAINER" --format '{{.Image}}' 2>/dev/null || true)
log "当前在用镜像 ${PREV_IMAGE:-none}"

# podman-compose 的 up -d 只比对配置哈希不认镜像 ID，重建前必须删掉同名容器
podman rm -f "$CONTAINER" 2>/dev/null || true
drop_stale_dnat

log "启动容器"
podman-compose -f "$COMPOSE" up -d || { echo "启动容器失败"; exit 1; }

sleep 5
log "端口映射"
podman port "$CONTAINER" || true

# 等待就绪：最长 3 分钟
log "等待就绪"
ready=0
for _ in $(seq 1 36); do
  if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done

if [ "$ready" != "1" ]; then
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
  podman rm -f "$CONTAINER" 2>/dev/null || true
  drop_stale_dnat
  podman-compose -f "$COMPOSE" up -d || { echo "回滚启动失败"; exit 1; }
  sleep 5
  for _ in $(seq 1 36); do
    if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
      echo "=== 已回滚到 $PREV_IMAGE，站点恢复；本次发布判定失败 ==="
      exit 1
    fi
    sleep 5
  done
  echo "=== 回滚后仍未就绪，需要人工介入 ==="
  podman logs --tail 80 "$CONTAINER" || true
  exit 1
fi

log "就绪，清理悬空镜像"
podman image prune -f || true
log "liteworkbench ready on :$PORT"
