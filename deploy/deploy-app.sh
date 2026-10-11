#!/usr/bin/env bash
# 应用发布：在云主机上拉取新镜像、重启应用容器并做就绪自检
# 用法：bash deploy-app.sh <镜像:tag>，工作目录为 docker-compose.app.yaml 所在目录
set -uo pipefail

IMAGE="${1:?用法: deploy-app.sh <镜像:tag>}"
CONTAINER=liteworkbench
COMPOSE_FILE=docker-compose.app.yaml
PORT=24399

# 每步都打点：失败时能直接看出死在哪一步
step() { echo "[deploy] $*"; }

# podman-compose 1.0.6 的 up -d 只比对配置哈希不比对镜像 ID，先删容器才能保证新镜像生效
# podman 3.x rootless 反复重建同名容器会在 CNI-HOSTPORT-DNAT 留下指向已删容器 IP 的历史规则，
# 导致新容器端口被转发到不存在的地址，重建前先清理同端口残留
# 注意：grep 无匹配返回 1，必须 || true 兜住，否则命令替换的非 0 状态会让脚本提前退出
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

step "拉取镜像 $IMAGE"
export LITEWORKBENCH_IMAGE="$IMAGE"
# 不走 set -e：关键步骤显式判失败，非关键步骤允许非零继续
podman-compose -f "$COMPOSE_FILE" pull || { echo "拉取镜像失败"; exit 1; }
podman rm -f "$CONTAINER" 2>/dev/null || true
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
