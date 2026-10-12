#!/usr/bin/env bash
# 应用发布：拉取新镜像、重建应用容器、清理端口转发残留并按健康端点做就绪自检
# 用法：bash deploy-app.sh <镜像:tag>，工作目录为 docker-compose.app.yaml 所在目录
set -uo pipefail

IMAGE="${1:?用法: deploy-app.sh <镜像:tag>}"
CONTAINER=liteworkbench
COMPOSE_FILE=docker-compose.app.yaml
PORT=24399

step() { echo "[deploy] $*"; }

# podman-compose 1.0.6 的 up -d 只比对配置哈希不认镜像 ID，重建前必须删掉同名容器
drop_container() {
  podman rm -f "$CONTAINER" 2>/dev/null || true
}

# rootless podman 重建后会留下指向已删容器的 CNI-HOSTPORT-DNAT 规则，导致宿主机端口不可达
drop_stale_dnat() {
  local dropped=0 ln
  while true; do
    ln=$(iptables -t nat -L CNI-HOSTPORT-DNAT -n --line-numbers 2>/dev/null | grep "$PORT" | head -1 | awk '{print $1}' || true)
    [ -z "$ln" ] && break
    iptables -t nat -D CNI-HOSTPORT-DNAT "$ln" 2>/dev/null || break
    dropped=$((dropped + 1))
    [ "$dropped" -gt 30 ] && break
  done
  step "清理 CNI-HOSTPORT-DNAT 残留规则 $dropped 条"
}

# 主机本地机密（初始密码等）放同目录 .env.app，不入仓库；缺失即跳过，配置仍走 CI 注入的变量
if [ -f .env.app ]; then
  step "加载主机本地环境变量 .env.app"
  set -a
  . ./.env.app
  set +a
fi

# 关键步骤显式判失败，非关键步骤允许非零继续，因此不开 set -e
step "拉取镜像 $IMAGE"
export LITEWORKBENCH_IMAGE="$IMAGE"
podman-compose -f "$COMPOSE_FILE" pull || { echo "拉取镜像失败"; exit 1; }
drop_container
drop_stale_dnat

step "启动容器"
podman-compose -f "$COMPOSE_FILE" up -d || { echo "启动容器失败"; exit 1; }

sleep 5
step "端口映射"
podman port "$CONTAINER" || true

step "等待就绪"
ready=0
for _ in $(seq 1 36); do
  if curl -fsS "http://127.0.0.1:$PORT/actuator/health" >/dev/null 2>&1; then
    ready=1
    break
  fi
  sleep 5
done

# 未就绪即失败并打印现场，避免把启动失败的容器留在线上
if [ "$ready" != "1" ]; then
  echo "=== 应用未就绪，打印容器与端口现场 ==="
  podman ps --format '{{.Names}} | {{.Status}} | {{.Ports}}'
  podman logs --tail 80 "$CONTAINER" || true
  ss -lntp 2>/dev/null | grep "$PORT" || echo "$PORT 未监听"
  exit 1
fi

step "就绪，清理悬空镜像"
podman image prune -f || true
step "liteworkbench ready on :$PORT"
